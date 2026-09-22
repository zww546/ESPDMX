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
 *          startHi/startLo = 起始通道号(1..1024) 的大端 16 位
 *          count           = 本段通道数 (1..255；256 会溢出为 0，上层必须分块 <=255)
 *   [0x02] 全黑     : 0x02              （所有通道置 0）
 *   [0x03] 全亮     : 0x03              （所有通道置 255）
 *   [0x04] 心跳/保活 : 0x04
 *
 * 通知帧（ESP32 -> App，来自 0xFF02）：
 *   [0x81] 状态: 0x81, statusByte   （bit0=DMX输出中）
 *   其余响应见 PROTOCOL.md；解析器在 DeviceMessages（纯函数，带单测）。
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
    const val CMD_REQ_STATE: Int = 0x05   // 请求整机状态（重连/重启后同步）

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
    const val CMD_MOVE: Int = 0x3A
    const val CMD_COPY: Int = 0x3B
    const val CMD_LIST_DIRS: Int = 0x3C

    // ---- RDM（v8，固件侧用 esp_dmx 的 controller API 实现）----
    const val CMD_RDM_SCAN: Int = 0x40        // + universe
    const val CMD_RDM_IDENTIFY: Int = 0x41    // + universe + uid(6) + on
    const val CMD_RDM_SET_ADDR: Int = 0x42    // + universe + uid(6) + addrHi + addrLo
    const val CMD_RDM_SET_ADDRS: Int = 0x44   // + universe + count + (uid(6) addr)* 批量改址

    // ESP32 → App notify 响应
    const val RESP_UPLOAD_RESULT: Int = 0x91
    const val RESP_FILE_LIST: Int = 0x92
    const val RESP_FILE_CHUNK: Int = 0x93
    const val RESP_FILE_END: Int = 0x94
    const val RESP_DELETE_RESULT: Int = 0x95
    const val RESP_DIR_RESULT: Int = 0x96
    const val RESP_DIRS_LIST: Int = 0x97
    const val RESP_DIRS_END: Int = 0x98
    // RDM 应答（0x89/0x8A/0x8B 是当时唯一空闲的一段，0x96~0x98 已被文件夹操作占用）
    const val RESP_RDM_SCAN: Int = 0x89      // count(1) universe(1) ok(1) errLen(1) err…
    const val RESP_RDM_DEVICE: Int = 0x8A    // 每台设备的全部参数，见 parseRdmDevice()
    const val RESP_RDM_RESULT: Int = 0x8B    // ok(1) errLen(1) err…（识别/改址的结果）

    /**
     * 宇宙号 → 面板上的 "A" / "B"。
     *
     * ⚠ 以前这个映射有**三套**写法：`FixtureInstance.label()`、`RdmDevice.addrLabel()`，
     *   外加 MainActivity 里 4 处内联 `if (universe == 1) "A" else "B"`。
     *   统一到这里，省得改一处分不清别处要不要跟着改。
     */
    fun bandLabel(universe: Int): String = if (universe == 1) "A" else "B"

    // ---- 状态同步（0x05 的应答，固件 → App）----
    // 0x82 flags(1) uptime(4,秒,大端) fxCount(1) progMask(1)
    const val RESP_STATE_HEAD: Int = 0x82
    // 0x83 seq(1) startHi startLo count(1) data[count]
    const val RESP_STATE_CHUNK: Int = 0x83
    // 0x84 slot(1) fxId(1) ampHi ampLo speedHi speedLo
    const val RESP_STATE_FX: Int = 0x84
    // 0x85 （结束）
    const val RESP_STATE_END: Int = 0x85

    /** 单个宇宙的通道数。 */
    const val UNIVERSE_SIZE = 512
    /** 支持的宇宙数（v6：双宇宙）。 */
    const val UNIVERSES = 2
    /** 全局通道数 = 宇宙数 × 512。ch 1..512 = 宇宙1，513..1024 = 宇宙2。 */
    const val MAX_CHANNELS = UNIVERSE_SIZE * UNIVERSES

    /** 全局通道 → 宇宙号（1-based）。 */
    fun universeOf(globalCh: Int): Int = ((globalCh - 1) / UNIVERSE_SIZE) + 1
    /** 全局通道 → 宇宙内地址（1-based）。 */
    fun addrInUniverse(globalCh: Int): Int = ((globalCh - 1) % UNIVERSE_SIZE) + 1
    /** 宇宙号 + 宇宙内地址 → 全局通道。 */
    fun toGlobal(universe: Int, addr: Int): Int =
        (universe.coerceIn(1, UNIVERSES) - 1) * UNIVERSE_SIZE + addr

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

    /** 0x05: 请求固件上报整机状态（512 通道 + 运行中效果 + 正在播放的程序）。 */
    fun encodeRequestState(): ByteArray = byteArrayOf(CMD_REQ_STATE.toByte())

    fun encodeProgClear(progId: Int = 0): ByteArray = byteArrayOf(CMD_PROG_CLEAR.toByte(), progId.toByte())
    fun encodeProgPlay(progId: Int = 0, loop: Boolean = true): ByteArray =
        byteArrayOf(CMD_PROG_PLAY.toByte(), progId.toByte(), (if (loop) 1 else 0).toByte())
    fun encodeProgStop(progId: Int = 0): ByteArray = byteArrayOf(CMD_PROG_STOP.toByte(), progId.toByte())
    fun encodeProgStopAll(): ByteArray = byteArrayOf(CMD_PROG_STOPALL.toByte())

    /**
     * 稀疏存步: 只记录动过的通道 (ch,val) 列表。
     * 帧: 0x12 prog_id timeHi timeLo count (chHi chLo val)*
     */
    /**
     * 程序"每步最多通道项数"。
     *
     * 协议里 count 只占 1 字节 → 上限 255；固件 `PROG_MAX_ITEMS_STEP` 与之一致。
     * 早期双方都是 64 且都**静默截断**：录制多台灯时超过 64 条的变化会被悄悄丢掉。
     * 现在 App 侧超限会明确提示（见 MainActivity.uploadProgramAndPlay）。
     */
    const val MAX_PROG_ITEMS_STEP = 255

    fun encodeProgAppendSparse(progId: Int, timeMs: Int, changes: List<Pair<Int, Int>>): ByteArray {
        val t = timeMs.coerceIn(0, 65535)
        val count = changes.size.coerceAtMost(MAX_PROG_ITEMS_STEP)
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
     * 配置并启动板载效果 v6（阵列目标 + 相位扩散）。
     * 帧: 0x20 slot fx_id
     *     pan panF tilt tiltF dim dimF r g b
     *     zoom zoomF focus focusF color gobo goboRot
     *     amp speed
     *     blade[0..7] shaperRot                       ← 到这里 57 字节（v5）
     *     stride count spread shape direction phase envelope   ← v6 追加 8 字节，共 65
     *
     * 阵列语义：第 i 台的某属性通道 = 该属性通道 + i×stride （i = 0..count-1）
     * 相位：第 i 台 = 基准 + step(i)×spread（direction: 0正序 1反序 2往返）
     * shape: 0正弦 1三角 2方波 3脉冲 4随机 5锯齿
     * envelope: 0无 1渐入 2渐出 3对称(中间强) 4两端强
     *
     * ⚠ 兼容性：旧固件只读前 57 字节，尾部会被自动忽略（退化为单台效果）。
     */
    fun encodeFxSet(slot: Int, fxId: Int,
                    pan: Int, panFine: Int, tilt: Int, tiltFine: Int,
                    dim: Int, dimFine: Int, r: Int, g: Int, b: Int,
                    zoom: Int, zoomFine: Int, focus: Int, focusFine: Int,
                    color: Int, gobo: Int, goboRot: Int,
                    amp16: Int, speed: Int,
                    blades: List<Int> = emptyList(), shaperRot: Int = 0,
                    stride: Int = 0, count: Int = 1, spread: Int = 0,
                    shape: Int = 0, direction: Int = 0, phase: Int = 0,
                    envelope: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        // v6: 57 字节基础 + 8 字节阵列参数 = 65 字节
        val out = ByteArray(65)
        out[0] = CMD_FX_SET.toByte()
        out[1] = slot.toByte()
        out[2] = fxId.toByte()
        var i = 3
        for (ch in listOf(pan, panFine, tilt, tiltFine, dim, dimFine, r, g, b,
                          zoom, zoomFine, focus, focusFine, color, gobo, goboRot)) {
            val b2 = u16(ch.coerceIn(0, MAX_CHANNELS))
            out[i++] = b2[0]; out[i++] = b2[1]
        }
        val ampB = u16(amp16.coerceIn(0, 65535))
        out[i++] = ampB[0]; out[i++] = ampB[1]
        val spdB = u16(speed.coerceIn(0, 65535))
        out[i++] = spdB[0]; out[i++] = spdB[1]
        // v5: 8 个切割片通道（不足补 0，超出截断）
        for (b in 0 until 8) {
            val v = if (b < blades.size) blades[b] else 0
            val bb = u16(v.coerceIn(0, MAX_CHANNELS))
            out[i++] = bb[0]; out[i++] = bb[1]
        }
        val sr = u16(shaperRot.coerceIn(0, MAX_CHANNELS))
        out[i++] = sr[0]; out[i++] = sr[1]
        // ---- v6 阵列参数（8 字节）----
        val st = u16(stride.coerceIn(0, MAX_CHANNELS))
        out[57] = st[0]; out[58] = st[1]
        out[59] = count.coerceIn(1, 255).toByte()
        out[60] = spread.coerceIn(0, 255).toByte()
        out[61] = shape.coerceIn(0, 5).toByte()
        out[62] = direction.coerceIn(0, 2).toByte()
        out[63] = phase.coerceIn(0, 255).toByte()
        out[64] = envelope.coerceIn(0, 4).toByte()
        return out
    }

    fun encodeFxStop(slot: Int): ByteArray = byteArrayOf(CMD_FX_STOP.toByte(), slot.toByte())
    fun encodeFxStopAll(): ByteArray = byteArrayOf(CMD_FX_STOPALL.toByte())

    // ---- 文件传输编码（全部支持 dir：相对 /fw 的目录，空串 = 根目录）----

    /** dir 参数编码: dirLen dir…（空串 = 单字节 0） */
    private fun dirPart(dir: String): ByteArray {
        val d = dir.toByteArray(Charsets.UTF_8)
        return byteArrayOf(d.size.toByte()) + d
    }

    /** 0x31: 上传开始 — dir+name 不含路径分隔符, size 为文件总字节数 */
    fun encodeUploadStart(dir: String, name: String, size: Int): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + n.size + 2)
        out[0] = CMD_UPLOAD_START.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        out[2 + dp.size + n.size] = ((size ushr 8) and 0xFF).toByte()
        out[3 + dp.size + n.size] = (size and 0xFF).toByte()
        return out
    }

    /** 0x32: 上传数据块 (seq 仅用于日志, 固件忽略首字节) */
    fun encodeUploadChunk(seq: Int, data: ByteArray): ByteArray =
        byteArrayOf(CMD_UPLOAD_CHUNK.toByte(), seq.toByte()) + data

    /** 0x33: 上传结束 */
    fun encodeUploadEnd(): ByteArray = byteArrayOf(CMD_UPLOAD_END.toByte())

    /**
     * 0x34: 列出设备文件。
     * 总是带上 `dirLen`（根目录 = `0x34 0x00`）：老固件只接受带参数的写法，
     * 发裸 `0x34` 会被丢弃（表现为“设备灯库/文件管理 加载不出”）。
     */
    fun encodeListFiles(dir: String): ByteArray =
        byteArrayOf(CMD_LIST_FILES.toByte()) + dirPart(dir)

    /** 0x35: 下载设备文件 */
    fun encodeDownloadFile(dir: String, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + n.size)
        out[0] = CMD_DOWNLOAD_FILE.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        return out
    }

    /** 0x36: 删除设备文件 */
    fun encodeDeleteFile(dir: String, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + n.size)
        out[0] = CMD_DELETE_FILE.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        return out
    }

    /** 0x37: 在 dir 下创建设备文件夹 */
    fun encodeMkdir(dir: String, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + n.size)
        out[0] = CMD_MKDIR.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        return out
    }

    /** 0x38: 删除 dir 下设备空文件夹 */
    fun encodeRmdir(dir: String, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + n.size)
        out[0] = CMD_RMDIR.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        return out
    }

    /** 0x39: 重命名 dir 下文件/文件夹：oldLen old… newLen new… */
    fun encodeRename(dir: String, oldName: String, newName: String): ByteArray {
        val o = oldName.toByteArray(Charsets.UTF_8)
        val n = newName.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val out = ByteArray(2 + dp.size + o.size + 1 + n.size)
        out[0] = CMD_RENAME.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = o.size.toByte()
        System.arraycopy(o, 0, out, 2 + dp.size, o.size)
        out[2 + dp.size + o.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 3 + dp.size + o.size, n.size)
        return out
    }

    /** 0x3A: 移动 dir 下条目到目标文件夹（dstDir 空 = 根目录） */
    fun encodeMove(dir: String, name: String, dstDir: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val ddp = dirPart(dstDir)
        val out = ByteArray(2 + dp.size + n.size + ddp.size)
        out[0] = CMD_MOVE.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        System.arraycopy(ddp, 0, out, 2 + dp.size + n.size, ddp.size)
        return out
    }

    /** 0x3B: 复制 dir 下条目到目标文件夹（dstDir 空 = 根目录；文件夹递归复制） */
    fun encodeCopy(dir: String, name: String, dstDir: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val dp = dirPart(dir)
        val ddp = dirPart(dstDir)
        val out = ByteArray(2 + dp.size + n.size + ddp.size)
        out[0] = CMD_COPY.toByte()
        System.arraycopy(dp, 0, out, 1, dp.size)
        out[1 + dp.size] = n.size.toByte()
        System.arraycopy(n, 0, out, 2 + dp.size, n.size)
        System.arraycopy(ddp, 0, out, 2 + dp.size + n.size, ddp.size)
        return out
    }

    /** 0x3C: 全量目录树（回 0x97 多帧 + 0x98 结束帧） */
    fun encodeListDirs(): ByteArray = byteArrayOf(CMD_LIST_DIRS.toByte())
}
