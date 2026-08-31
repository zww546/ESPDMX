package com.example.stagedmx

import java.util.UUID

/**
 * DMX over BLE 协议定义（App 端）。
 * ESP32-S3 端需实现完全相同的 UUID 与帧格式。
 *
 * GATT 结构：
 *   Service  0xFF00
 *     Char 0xFF01  Write / WriteNoResponse  —— App 下发指令
 *     Char 0xFF02  Notify                   —— ESP32 上报状态（可选）
 *
 * 指令帧（App -> ESP32，写入 0xFF01）：
 *   [0x01] 设置连续通道段: 0x01, startHi, startLo, count, v0, v1, ... v(count-1)
 *          startHi/startLo = 起始通道号(1..512) 的大端 16 位
 *          count           = 本段通道数 (1..512)
 *   [0x02] 全黑     : 0x02              （所有通道置 0）
 *   [0x03] 全亮     : 0x03              （所有通道置 255）
 *   [0x04] 心跳/保活 : 0x04
 *
 * 通知帧（ESP32 -> App，来自 0xFF02，可选）：
 *   [0x81] 状态: 0x81, statusByte   （bit0=DMX输出中）
 */
object DmxProtocol {

    fun uuid16(hex: String): UUID =
        UUID.fromString("0000$hex-0000-1000-8000-00805f9b34fb")

    val SERVICE_UUID: UUID = uuid16("ff00")
    val CHAR_WRITE_UUID: UUID = uuid16("ff01")
    val CHAR_NOTIFY_UUID: UUID = uuid16("ff02")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_SET_RANGE: Int = 0x01
    const val CMD_BLACKOUT: Int = 0x02
    const val CMD_FULL_ON: Int = 0x03
    const val CMD_PING: Int = 0x04

    // 板载程序(多程序并行): 0x10-0x15 带 prog_id
    const val CMD_PROG_CLEAR: Int  = 0x10  // + prog_id
    const val CMD_PROG_APPEND: Int = 0x12  // + prog_id + timeHi + timeLo + count + (chHi chLo val)*
    const val CMD_PROG_PLAY: Int   = 0x13  // + prog_id + flags(bit0=loop)
    const val CMD_PROG_STOP: Int   = 0x14  // + prog_id
    const val CMD_PROG_STOPALL: Int = 0x15

    // 板载效果(独立层, 离线运行): 0x20-0x22
    const val CMD_FX_SET: Int = 0x20       // + slot fx_id pan tilt dim r g b amp speed
    const val CMD_FX_STOP: Int = 0x21      // + slot
    const val CMD_FX_STOPALL: Int = 0x22

    // 文件传输(灯库互传)
    const val CMD_UPLOAD_START: Int = 0x31
    const val CMD_UPLOAD_CHUNK: Int = 0x32
    const val CMD_UPLOAD_END: Int = 0x33
    const val CMD_LIST_FILES: Int = 0x34
    const val CMD_DOWNLOAD_FILE: Int = 0x35
    const val CMD_DELETE_FILE: Int = 0x36
    const val CMD_MKDIR: Int = 0x37
    const val CMD_RMDIR: Int = 0x38
    const val CMD_RENAME: Int = 0x39

    // ESP32 → App notify 响应
    const val RESP_UPLOAD_RESULT: Int = 0x91
    const val RESP_FILE_LIST: Int = 0x92
    const val RESP_FILE_CHUNK: Int = 0x93
    const val RESP_FILE_END: Int = 0x94
    const val RESP_DELETE_RESULT: Int = 0x95
    const val RESP_DIR_RESULT: Int = 0x96

    const val MAX_CHANNELS = 512

    /** 效果类型（与 FxEngine.presets 对齐）。 */
    const val FX_CIRCLE = 1
    const val FX_PAN_SWING = 2
    const val FX_TILT_SWING = 3
    const val FX_STROBE = 4
    const val FX_RGB = 5

    /**
     * 编码"设置连续通道段"帧。
     * @param start 1-based 起始通道号
     * @param values 该段各通道的值 (0..255)
     */
    fun encodeSetRange(start: Int, values: ByteArray): ByteArray {
        require(start in 1..MAX_CHANNELS) { "start out of range" }
        require(values.isNotEmpty() && values.size <= MAX_CHANNELS) { "bad count" }
        val out = ByteArray(4 + values.size)
        out[0] = CMD_SET_RANGE.toByte()
        out[1] = ((start ushr 8) and 0xFF).toByte()
        out[2] = (start and 0xFF).toByte()
        out[3] = values.size.toByte()          // 1..255；256 段会溢出为 0，故上层分块 <=255
        System.arraycopy(values, 0, out, 4, values.size)
        return out
    }

    fun encodeBlackout(): ByteArray = byteArrayOf(CMD_BLACKOUT.toByte())
    fun encodeFullOn(): ByteArray = byteArrayOf(CMD_FULL_ON.toByte())
    fun encodePing(): ByteArray = byteArrayOf(CMD_PING.toByte())

    fun encodeProgClear(progId: Int = 0): ByteArray = byteArrayOf(CMD_PROG_CLEAR.toByte(), progId.toByte())
    fun encodeProgPlay(progId: Int = 0, loop: Boolean = true): ByteArray =
        byteArrayOf(CMD_PROG_PLAY.toByte(), progId.toByte(), (if (loop) 1 else 0).toByte())
    fun encodeProgStop(progId: Int = 0): ByteArray = byteArrayOf(CMD_PROG_STOP.toByte(), progId.toByte())
    fun encodeProgStopAll(): ByteArray = byteArrayOf(CMD_PROG_STOPALL.toByte())

    /**
     * 稀疏存步: 只记录动过的通道 (ch,val) 列表。
     * 帧: 0x12 prog_id timeHi timeLo count (chHi chLo val)*
     */
    fun encodeProgAppendSparse(progId: Int, timeMs: Int, changes: List<Pair<Int, Int>>): ByteArray {
        val t = timeMs.coerceIn(0, 65535)
        val count = changes.size.coerceAtMost(64)
        val out = ByteArray(5 + count * 3)
        out[0] = CMD_PROG_APPEND.toByte()
        out[1] = progId.toByte()
        out[2] = ((t ushr 8) and 0xFF).toByte()
        out[3] = (t and 0xFF).toByte()
        out[4] = count.toByte()
        for (i in 0 until count) {
            val (ch, v) = changes[i]
            out[5 + i*3] = ((ch ushr 8) and 0xFF).toByte()
            out[6 + i*3] = (ch and 0xFF).toByte()
            out[7 + i*3] = v.coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * 配置并启动板载效果 v4（支持 fine 通道 + 16bit 幅度 + 速度 + 属性通道）。
     * 帧: 0x20 slot fx_id
     *     pan panF tilt tiltF dim dimF r g b
     *     zoom zoomF focus focusF color gobo goboRot
     *     amp speed
     * speed：速度 0..65535，越大越快（8.8 定点：速度/256 = 每 10ms tick 推进的 1/256 相位步数）。
     */
    fun encodeFxSet(slot: Int, fxId: Int,
                    pan: Int, panFine: Int, tilt: Int, tiltFine: Int,
                    dim: Int, dimFine: Int, r: Int, g: Int, b: Int,
                    zoom: Int, zoomFine: Int, focus: Int, focusFine: Int,
                    color: Int, gobo: Int, goboRot: Int,
                    amp16: Int, speed: Int): ByteArray {
        fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        val out = ByteArray(39)
        out[0] = CMD_FX_SET.toByte()
        out[1] = slot.toByte()
        out[2] = fxId.toByte()
        var i = 3
        for (ch in listOf(pan, panFine, tilt, tiltFine, dim, dimFine, r, g, b,
                          zoom, zoomFine, focus, focusFine, color, gobo, goboRot)) {
            val b2 = u16(ch.coerceIn(0, 512))
            out[i++] = b2[0]; out[i++] = b2[1]
        }
        val ampB = u16(amp16.coerceIn(0, 65535))
        out[i++] = ampB[0]; out[i++] = ampB[1]
        val spdB = u16(speed.coerceIn(0, 65535))
        out[i++] = spdB[0]; out[i++] = spdB[1]
        return out
    }

    fun encodeFxStop(slot: Int): ByteArray = byteArrayOf(CMD_FX_STOP.toByte(), slot.toByte())
    fun encodeFxStopAll(): ByteArray = byteArrayOf(CMD_FX_STOPALL.toByte())

    // ---- 文件传输编码 ----

    /** 0x31: 上传开始 — name不含路径,size为文件总字节数 */
    fun encodeUploadStart(name: String, size: Int): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + n.size)
        out[0] = CMD_UPLOAD_START.toByte()
        out[1] = n.size.toByte()
        System.arraycopy(n, 0, out, 2, n.size)
        out[2 + n.size] = ((size ushr 8) and 0xFF).toByte()
        out[3 + n.size] = (size and 0xFF).toByte()
        return out
    }

    /** 0x32: 上传数据块 (seq 仅用于日志, 固件忽略首字节) */
    fun encodeUploadChunk(seq: Int, data: ByteArray): ByteArray =
        byteArrayOf(CMD_UPLOAD_CHUNK.toByte(), seq.toByte()) + data

    /** 0x33: 上传结束 */
    fun encodeUploadEnd(): ByteArray = byteArrayOf(CMD_UPLOAD_END.toByte())

    /** 0x34: 列出设备文件 */
    fun encodeListFiles(): ByteArray = byteArrayOf(CMD_LIST_FILES.toByte())

    /** 0x35: 下载设备文件 */
    fun encodeDownloadFile(name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + n.size)
        out[0] = CMD_DOWNLOAD_FILE.toByte()
        out[1] = n.size.toByte()
        System.arraycopy(n, 0, out, 2, n.size)
        return out
    }

    /** 0x36: 删除设备文件 */
    fun encodeDeleteFile(name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + n.size)
        out[0] = CMD_DELETE_FILE.toByte()
        out[1] = n.size.toByte()
        System.arraycopy(n, 0, out, 2, n.size)
        return out
    }

    /** 0x37: 创建设备文件夹 */
    fun encodeMkdir(name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + n.size)
        out[0] = CMD_MKDIR.toByte()
        out[1] = n.size.toByte()
        System.arraycopy(n, 0, out, 2, n.size)
        return out
    }

    /** 0x38: 删除设备空文件夹 */
    fun encodeRmdir(name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + n.size)
        out[0] = CMD_RMDIR.toByte()
        out[1] = n.size.toByte()
        System.arraycopy(n, 0, out, 2, n.size)
        return out
    }

    /** 0x39: 重命名设备文件/文件夹：oldLen old… newLen new… */
    fun encodeRename(oldName: String, newName: String): ByteArray {
        val o = oldName.toByteArray(Charsets.UTF_8)
        val n = newName.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + o.size + 1 + n.size)
        out[0] = CMD_RENAME.toByte()
        out[1] = o.size.toByte()
        System.arraycopy(o, 0, out, 2, o.size)
        out[2 + o.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 3 + o.size, n.size)
        return out
    }
}
