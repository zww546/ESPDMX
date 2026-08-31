package com.example.stagedmx

import android.os.Handler
import android.os.Looper

/**
 * 512 通道 DMX 状态机 + 发送节流 + 主控(Grand Master)缩放。
 * values[] 保存原始通道值(0..255，UI 显示的就是它)；实际下发时按 master 缩放：
 *   输出 = raw * master / 255
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

    @Volatile private var master = 255    // 0..255

    private val flushIntervalMs = 33L     // ~30fps

    private fun scale(raw: Int): Int = (raw and 0xFF) * master / 255

    /** 读取通道原始值 (1-based)。 */
    fun get(ch: Int): Int = synchronized(lock) {
        if (ch < 1 || ch > values.size) 0
        else values[ch - 1].toInt() and 0xFF
    }

    fun snapshot(): IntArray = synchronized(lock) { IntArray(values.size) { values[it].toInt() and 0xFF } }

    /** 仅写内部值, 不发 BLE——供本地镜像播放更新 UI 推子用。 */
    fun applyAllLocal(newValues: IntArray) {
        synchronized(lock) {
            val n = minOf(newValues.size, values.size)
            for (i in 0 until n) values[i] = newValues[i].coerceIn(0, 255).toByte()
        }
    }

    /** 主控百分比 0..100。 */
    fun masterPct(): Int = (master * 100 + 127) / 255

    fun setMasterPct(pct: Int) {
        val m = (pct.coerceIn(0, 100) * 255 / 100)
        synchronized(lock) {
            master = m
            if (usedMax > 0) {            // 全体已用通道重发(缩放后)
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

    /** 将前 count 个通道(1..count)统一置为 value，仅下发这些通道（全黑/全亮只管当前通道数）。 */
    fun setUniform(count: Int, value: Int) {
        val n = count.coerceIn(1, DmxProtocol.MAX_CHANNELS)
        val v = value.coerceIn(0, 255).toByte()
        synchronized(lock) {
            for (i in 0 until n) values[i] = v
            if (0 < dirtyLo) dirtyLo = 0
            if (n - 1 > dirtyHi) dirtyHi = n - 1
            if (n > usedMax) usedMax = n
        }
        scheduleFlush()
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
            val seg = ByteArray(len) { scale(chunk[offset + it].toInt()).toByte() }
            ble.send(DmxProtocol.encodeSetRange(lo + offset + 1, seg), coalesceRealtime = true)
            offset += len
        }
    }

    /** 整帧下发全部 512 通道（缩放后，分块）。 */
    fun sendFullFrame() {
        val copy = synchronized(lock) { values.copyOf() }
        var start = 0
        while (start < copy.size) {
            val len = minOf(255, copy.size - start)
            val seg = ByteArray(len) { scale(copy[start + it].toInt()).toByte() }
            ble.send(DmxProtocol.encodeSetRange(start + 1, seg))
            start += len
        }
    }

    /** 整帧原始值(不经主控缩放)下发到板子——供程序上传用(板载存储需全域值)。 */
    fun sendRawFrame(values: IntArray) {
        var start = 0
        while (start < values.size && start < DmxProtocol.MAX_CHANNELS) {
            val len = minOf(255, values.size - start)
            val seg = ByteArray(len) { values[start + it].coerceIn(0, 255).toByte() }
            ble.send(DmxProtocol.encodeSetRange(start + 1, seg))
            start += len
        }
    }

    fun sendProgClear(progId: Int = 0) = ble.send(DmxProtocol.encodeProgClear(progId))

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
                  amp16: Int, speed: Int) =
        ble.send(DmxProtocol.encodeFxSet(slot, fxId, pan, panFine, tilt, tiltFine,
            dim, dimFine, r, g, b, zoom, zoomFine, focus, focusFine,
            color, gobo, goboRot, amp16, speed))
    fun sendFxStop(slot: Int) = ble.send(DmxProtocol.encodeFxStop(slot))
    fun sendFxStopAll() = ble.send(DmxProtocol.encodeFxStopAll())

    /** 通用自定义命令（cmd + 1字节参数）。 */
    fun sendRawCmd(cmd: Int, arg: Int = 0) {
        ble.send(byteArrayOf(0xA0.toByte(), cmd.toByte(), arg.toByte()))
    }

    // ---- 文件传输 ----
    fun sendUploadStart(name: String, size: Int) = ble.send(DmxProtocol.encodeUploadStart(name, size))
    fun sendUploadChunk(seq: Int, data: ByteArray) = ble.send(DmxProtocol.encodeUploadChunk(seq, data))
    fun sendUploadEnd() = ble.send(DmxProtocol.encodeUploadEnd())
    fun sendListFiles() = ble.send(DmxProtocol.encodeListFiles())
    fun sendDownloadFile(name: String) = ble.send(DmxProtocol.encodeDownloadFile(name))
    fun sendDeleteFile(name: String) = ble.send(DmxProtocol.encodeDeleteFile(name))
    fun sendMkdir(name: String) = ble.send(DmxProtocol.encodeMkdir(name))
    fun sendRmdir(name: String) = ble.send(DmxProtocol.encodeRmdir(name))
    fun sendRename(oldName: String, newName: String) = ble.send(DmxProtocol.encodeRename(oldName, newName))
    fun sendMove(name: String, dir: String) = ble.send(DmxProtocol.encodeMove(name, dir))
}
