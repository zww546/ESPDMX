package com.example.stagedmx

import android.os.Handler
import android.os.Looper

/**
 * 512 通道 DMX 状态机 + 发送节流 + 主控(Grand Master)缩放。
 * values[] 保存原始通道值(0..255，UI 显示的就是它)；实际下发时按 master 缩放：
 *   输出 = raw * master / 255
 *
 * **主控亮度只作用于“调光(DIM)”通道**（各实例灯库中 attribute=dim 的真实 DMX 地址）。
 * 早期版本对所有通道统一缩放，导致拉总控时摇头灯的水平/垂直、图案、棱镜等一起变小，
 * 属于明显的功能错误；现在只有被登记的调光通道参与缩放，其它通道原值下发。
 * 若一盏灯都没登记（纯裸通道模式，无灯库），退回“全部通道缩放”的旧行为。
 *
 * 滑条高频改值时以 ~30Hz 把"脏"通道段批量下发，避免刷爆 BLE。
 */
class DmxEngine(private val ble: BleManager) {

    private val values = ByteArray(DmxProtocol.MAX_CHANNELS) // 原始值 0..511
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private var dirtyLo = Int.MAX_VALUE   // 0-based
    private var dirtyHi = Int.MIN_VALUE
    private var flushScheduled = false
    private var usedMax = 0               // 已用到的最高通道(1-based)，主控缩放范围

    /** 参与主控亮度缩放的通道（1-based 真实 DMX 地址）。空 = 裸通道模式，全通道缩放。 */
    private val dimmerChannels = mutableSetOf<Int>()

    @Volatile private var master = 255    // 0..255

    private val flushIntervalMs = 33L     // ~30fps

    /** 按主控缩放：只有调光通道参与。 */
    private fun scale(ch: Int, raw: Int): Int =
        if (dimmerChannels.isEmpty() || dimmerChannels.contains(ch)) (raw and 0xFF) * master / 255
        else (raw and 0xFF)

    /** 登记参与主控亮度的调光通道（由 MainActivity 按当前所有实例的灯库刷新）。 */
    fun setDimmerChannels(channels: Collection<Int>) {
        synchronized(lock) {
            dimmerChannels.clear()
            channels.forEach { if (it in 1..DmxProtocol.MAX_CHANNELS) dimmerChannels.add(it) }
            if (usedMax > 0) { if (0 < dirtyLo) dirtyLo = 0; if (usedMax - 1 > dirtyHi) dirtyHi = usedMax - 1 }
        }
        scheduleFlush()
    }

    /** 读取通道原始值 (1-based)。 */
    fun get(ch: Int): Int = synchronized(lock) {
        if (ch < 1 || ch > values.size) 0
        else values[ch - 1].toInt() and 0xFF
    }

    fun snapshot(): IntArray = synchronized(lock) { IntArray(values.size) { values[it].toInt() and 0xFF } }

    /** 主控百分比 0..100。 */
    fun masterPct(): Int = (master * 100 + 127) / 255

    fun setMasterPct(pct: Int) {
        val m = (pct.coerceIn(0, 100) * 255 / 100)
        synchronized(lock) {
            master = m
            if (usedMax > 0) {            // 重发受影响的通道（调光通道 / 或全部）
                if (0 < dirtyLo) dirtyLo = 0
                if (usedMax - 1 > dirtyHi) dirtyHi = usedMax - 1
            }
        }
        scheduleFlush()
    }

    /** 设置单通道 (1-based)，值 0..255；节流后自动下发。 */
    fun set(ch: Int, value: Int) {
        val idx = ch - 1
        if (idx < 0 || idx >= values.size) return
        val v = value.coerceIn(0, 255)
        synchronized(lock) {
            if ((values[idx].toInt() and 0xFF) == v) return
            values[idx] = v.toByte()
            if (idx < dirtyLo) dirtyLo = idx
            if (idx > dirtyHi) dirtyHi = idx
            if (ch > usedMax) usedMax = ch
        }
        scheduleFlush()
    }

    /** 批量整场覆盖（场景/步序调用），立即整帧下发。 */
    fun applyAll(newValues: IntArray) {
        synchronized(lock) {
            val n = minOf(newValues.size, values.size)
            for (i in 0 until n) values[i] = newValues[i].coerceIn(0, 255).toByte()
            dirtyLo = Int.MAX_VALUE; dirtyHi = Int.MIN_VALUE
            usedMax = values.size
        }
        sendFullFrame()
    }

    /** 将 [lo..hi]（1-based 闭区间）统一置为 value。 */
    fun setRangeUniform(lo: Int, hi: Int, value: Int) {
        val l = lo.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        val h = hi.coerceIn(l, DmxProtocol.MAX_CHANNELS)
        val v = value.coerceIn(0, 255).toByte()
        synchronized(lock) {
            for (i in l - 1 until h) values[i] = v
            if (l - 1 < dirtyLo) dirtyLo = l - 1
            if (h - 1 > dirtyHi) dirtyHi = h - 1
            if (h > usedMax) usedMax = h
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        main.postDelayed({ flushDirty() }, flushIntervalMs)
    }

    /**
     * 下发脏区间。
     *
     * ⚠ 合并语义交给 BleManager 的 FrameKind.Control：一次刷新的所有分块都是 Control，
     *   队列积压时被取代的永远是**整个**旧帧（不是旧帧的前半段）。
     *   旧实现在这里逐块传 coalesceRealtime=true，导致整帧刷新会被拦腰截断 → 固件收到半帧。
     */
    private fun flushDirty() {
        val lo: Int; val hi: Int
        val chunk: ByteArray
        synchronized(lock) {
            flushScheduled = false
            if (dirtyLo > dirtyHi) return
            lo = dirtyLo; hi = dirtyHi
            dirtyLo = Int.MAX_VALUE; dirtyHi = Int.MIN_VALUE
            chunk = values.copyOfRange(lo, hi + 1)
        }
        var offset = 0
        while (offset < chunk.size) {
            val len = minOf(255, chunk.size - offset)
            val seg = ByteArray(len) { scale(lo + offset + it + 1, chunk[offset + it].toInt()).toByte() }
            ble.send(DmxProtocol.encodeSetRange(lo + offset + 1, seg), BleManager.FrameKind.Control)
            offset += len
        }
    }

    /** 整帧下发全部通道（缩放后，分块）。 */
    fun sendFullFrame() {
        val copy = synchronized(lock) { values.copyOf() }
        var start = 0
        while (start < copy.size) {
            val len = minOf(255, copy.size - start)
            val seg = ByteArray(len) { scale(start + it + 1, copy[start + it].toInt()).toByte() }
            ble.send(DmxProtocol.encodeSetRange(start + 1, seg), BleManager.FrameKind.Control)
            start += len
        }
    }

    fun sendProgClear(progId: Int = 0) = ble.send(DmxProtocol.encodeProgClear(progId))

    /** 请求固件上报整机状态（0x05）。 */
    fun sendRequestState() = ble.send(DmxProtocol.encodeRequestState())

    /**
     * 采纳固件上报的通道状态（仅改本地值，不回发）。
     * 固件里的调光通道值是“主控缩放后”的，这里按当前主控反算回原始值，
     * 否则主控 <100% 时会被二次缩放。
     */
    fun adoptDeviceState(dev: IntArray) {
        synchronized(lock) {
            val n = minOf(dev.size, values.size)
            for (i in 0 until n) {
                val ch = i + 1
                var v = dev[i].coerceIn(0, 255)
                if (master in 1..254 && (dimmerChannels.isEmpty() || dimmerChannels.contains(ch))) {
                    v = ((v * 255 + master / 2) / master).coerceIn(0, 255)
                }
                values[i] = v.toByte()
            }
        }
    }
    /** 稀疏存步：只上传动过的通道 (ch,val) 列表。 */
    fun sendProgAppendSparse(progId: Int, timeMs: Int, changes: List<Pair<Int, Int>>) =
        ble.send(DmxProtocol.encodeProgAppendSparse(progId, timeMs, changes))
    fun sendProgPlay(progId: Int = 0, loop: Boolean = true) = ble.send(DmxProtocol.encodeProgPlay(progId, loop))
    fun sendProgStop(progId: Int = 0) = ble.send(DmxProtocol.encodeProgStop(progId))
    fun sendProgStopAll() = ble.send(DmxProtocol.encodeProgStopAll())

    // ---- 板载效果（离线运行）----
    fun sendFxSet(slot: Int, fxId: Int,
                  pan: Int, panFine: Int, tilt: Int, tiltFine: Int,
                  dim: Int, dimFine: Int, r: Int, g: Int, b: Int,
                  zoom: Int, zoomFine: Int, focus: Int, focusFine: Int,
                  color: Int, gobo: Int, goboRot: Int,
                  amp16: Int, speed: Int,
                  blades: List<Int> = emptyList(), shaperRot: Int = 0,
                  stride: Int = 0, count: Int = 1, spread: Int = 0,
                  shape: Int = 0, direction: Int = 0, phase: Int = 0,
                  envelope: Int = 0) =
        ble.send(DmxProtocol.encodeFxSet(slot, fxId, pan, panFine, tilt, tiltFine,
            dim, dimFine, r, g, b, zoom, zoomFine, focus, focusFine,
            color, gobo, goboRot, amp16, speed, blades, shaperRot,
            stride, count, spread, shape, direction, phase, envelope))
    fun sendFxStop(slot: Int) = ble.send(DmxProtocol.encodeFxStop(slot))
    fun sendFxStopAll() = ble.send(DmxProtocol.encodeFxStopAll())

    /** 通用自定义命令（cmd + 1字节参数）。 */
    fun sendRawCmd(cmd: Int, arg: Int = 0) {
        ble.send(byteArrayOf(0xA0.toByte(), cmd.toByte(), arg.toByte()))
    }

    /** 直接发送一帧已编码好的指令（RDM 等自定义帧用）。 */
    fun sendRaw(frame: ByteArray) = ble.send(frame)

    // ---- 文件传输（dir = 相对 /fw 目录，空串 = 根目录）----
    fun sendUploadStart(dir: String, name: String, size: Int) = ble.send(DmxProtocol.encodeUploadStart(dir, name, size))
    fun sendUploadChunk(seq: Int, data: ByteArray) = ble.send(DmxProtocol.encodeUploadChunk(seq, data))
    fun sendUploadEnd() = ble.send(DmxProtocol.encodeUploadEnd())
    fun sendListFiles(dir: String = "") = ble.send(DmxProtocol.encodeListFiles(dir))
    fun sendDownloadFile(dir: String, name: String) = ble.send(DmxProtocol.encodeDownloadFile(dir, name))
    fun sendDeleteFile(dir: String, name: String) = ble.send(DmxProtocol.encodeDeleteFile(dir, name))
    fun sendMkdir(dir: String, name: String) = ble.send(DmxProtocol.encodeMkdir(dir, name))
    fun sendRmdir(dir: String, name: String) = ble.send(DmxProtocol.encodeRmdir(dir, name))
    fun sendRename(dir: String, oldName: String, newName: String) = ble.send(DmxProtocol.encodeRename(dir, oldName, newName))
    fun sendMove(dir: String, name: String, dstDir: String) = ble.send(DmxProtocol.encodeMove(dir, name, dstDir))
    fun sendCopy(dir: String, name: String, dstDir: String) = ble.send(DmxProtocol.encodeCopy(dir, name, dstDir))
    fun sendListDirs() = ble.send(DmxProtocol.encodeListDirs())
}
