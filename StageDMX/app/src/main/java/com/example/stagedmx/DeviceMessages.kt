package com.example.stagedmx

import com.example.stagedmx.Msg.DeleteResult
import com.example.stagedmx.Msg.DirResult
import com.example.stagedmx.Msg.DirsEnd
import com.example.stagedmx.Msg.DirsList
import com.example.stagedmx.Msg.FileChunk
import com.example.stagedmx.Msg.FileEnd
import com.example.stagedmx.Msg.FileList
import com.example.stagedmx.Msg.StateChunk
import com.example.stagedmx.Msg.StateEnd
import com.example.stagedmx.Msg.StateFx
import com.example.stagedmx.Msg.StateHead
import com.example.stagedmx.Msg.UploadResult

/**
 * 设备通知帧的解析结果。未知或非法帧 → 解析返回 null（调用方直接忽略）。
 *
 * 独立于 [DeviceMessages] 声明：Kotlin 里 `object` 内部的嵌套类型无法用
 * `DeviceMessages.Msg.X` 访问（会被当成伴生对象成员），放顶层最省事也最清晰。
 */
sealed interface Msg {
    /**
     * 0x82 状态同步头：uptime + 效果数 + 程序位图，v9 起尾部追加 DMX 遥测。
     *
     * DMX 字段是可选的：固件 v8 只发 8 字节，所以这里给默认值，
     * 新旧固件混用不会越界也不会崩（App 可能比固件新）。
     */
    data class StateHead(
        val uptimeSec: Long,
        val fxCount: Int,
        val progMask: Int,
        /** bit0=U1 发送正常, bit1=U2 发送正常；-1 = 旧固件无此字段 */
        val dmxOk: Int = -1,
        /** 累计失败帧 */
        val dmxFails: Long = -1,
        val fps1: Int = -1,
        val fps2: Int = -1,
    ) : Msg
    /** 0x83 通道快照分块。start 为 1-based 全局通道号；values[i] 对应 start+i。 */
    data class StateChunk(val start: Int, val values: IntArray) : Msg
    /** 0x84 运行中的效果槽。 */
    data class StateFx(val slot: Int, val fxId: Int, val amp16: Int, val speed: Int) : Msg
    /** 0x85 状态同步结束。 */
    data object StateEnd : Msg

    /** 0x91 上传结果。 */
    data class UploadResult(val ok: Boolean) : Msg
    /** 0x92 文件列表（可能分多帧，调用方累加）。 */
    data class FileList(val files: List<DeviceMessages.DeviceFile>) : Msg
    /** 0x93 下载数据块。 */
    data class FileChunk(val seq: Int, val totalChunks: Int, val data: ByteArray) : Msg
    /** 0x94 下载结束。 */
    data class FileEnd(val ok: Boolean) : Msg
    /** 0x95 删除结果。 */
    data class DeleteResult(val ok: Boolean) : Msg
    /** 0x96 目录操作结果（mkdir/rmdir/rename/move/copy）。 */
    data class DirResult(val ok: Boolean) : Msg
    /** 0x97 全量目录列表分帧（路径可能分多帧，调用方累加）。 */
    data class DirsList(val dirs: List<String>) : Msg
    /** 0x98 目录收集完成。 */
    data object DirsEnd : Msg
}

/**
 * 设备通知帧（0xFF02）的解析器 —— **纯函数，无 Android 依赖**。
 *
 * 从 MainActivity.onNotify 里抽出来的原因：
 *  1. 这段 11 分支的二进制协议解析原来是 Activity 的一部分，3015 行里最难验证的一块，
 *     却完全无法单测（需要真机 + BLE）。
 *  2. 原来用一串 `data.size >= N` 的松散校验，短帧/越界帧有读越界的风险
 *     （例如 0x92 只校验了 `p + nl + 3 > data.size`，却没校验读完 name 后还能读到 type/size）。
 *  3. 解析与副作用混在一起，无法复用也无法测。
 *
 * 现在：**解析在这里（可单测），副作用在 MainActivity**（switch on sealed type）。
 * 所有多字节字段一律大端，与 PROTOCOL.md / 固件一致。
 */
object DeviceMessages {

    /** 一个设备端文件/文件夹条目（0x92 / 0x97 共用语义）。 */
    data class DeviceFile(val name: String, val size: Int, val isDir: Boolean = false)

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun u16be(d: ByteArray, i: Int): Int = (u8(d[i]) shl 8) or u8(d[i + 1])

    private fun u32be(d: ByteArray, i: Int): Long =
        (u8(d[i]).toLong() shl 24) or (u8(d[i + 1]).toLong() shl 16) or
        (u8(d[i + 2]).toLong() shl 8) or u8(d[i + 3]).toLong()

    /**
     * 解析一帧通知。非法/未知/短帧一律返回 null。
     *
     * ⚠ 所有分支都必须先做长度校验再读下标；这是这一段历史的越界来源。
     */
    fun parse(data: ByteArray): Msg? {
        if (data.isEmpty()) return null
        return when (u8(data[0])) {
            DmxProtocol.RESP_STATE_HEAD -> {
                if (data.size < 8) return null
                // v9 起为 16 字节（尾部带 DMX 遥测）；老固件 8 字节 → 走默认值
                if (data.size >= 16) {
                    StateHead(
                        uptimeSec = u32be(data, 2),
                        fxCount = u8(data[6]),
                        progMask = u8(data[7]),
                        dmxOk = u8(data[8]),
                        dmxFails = u32be(data, 9),
                        fps1 = u8(data[13]),
                        fps2 = u8(data[14]),
                    )
                } else {
                    StateHead(u32be(data, 2), u8(data[6]), u8(data[7]))
                }
            }

            DmxProtocol.RESP_STATE_CHUNK -> {
                if (data.size < 5) return null
                val start = u16be(data, 2)
                val count = u8(data[4])
                if (start < 1 || count == 0 || data.size < 5 + count) return null
                StateChunk(start, IntArray(count) { u8(data[5 + it]) })
            }

            DmxProtocol.RESP_STATE_FX -> {
                if (data.size < 7) return null
                StateFx(u8(data[1]), u8(data[2]), u16be(data, 3), u16be(data, 5))
            }

            DmxProtocol.RESP_STATE_END -> StateEnd

            DmxProtocol.RESP_UPLOAD_RESULT -> {
                if (data.size < 2) return null
                UploadResult(u8(data[1]) == 0)
            }

            DmxProtocol.RESP_FILE_LIST -> {
                if (data.size < 2) return null
                FileList(parseFileList(data))
            }

            DmxProtocol.RESP_FILE_CHUNK -> {
                if (data.size < 4) return null
                val dataLen = u8(data[3])
                if (data.size < 4 + dataLen) return null
                FileChunk(u8(data[1]), u8(data[2]), data.copyOfRange(4, 4 + dataLen))
            }

            DmxProtocol.RESP_FILE_END -> {
                if (data.size < 2) return null
                FileEnd(u8(data[1]) == 0)
            }

            DmxProtocol.RESP_DELETE_RESULT -> {
                if (data.size < 2) return null
                DeleteResult(u8(data[1]) == 0)
            }

            DmxProtocol.RESP_DIR_RESULT -> {
                if (data.size < 2) return null
                DirResult(u8(data[1]) == 0)
            }

            DmxProtocol.RESP_DIRS_LIST -> {
                if (data.size < 2) return null
                DirsList(parseDirsList(data))
            }

            DmxProtocol.RESP_DIRS_END -> DirsEnd

            else -> null
        }
    }

    /**
     * 0x92 帧：count [nameLen name type sizeHi sizeLo]×count
     *
     * 每条记录需要 `1 + nl + 3` 字节；任一记录放不下（截断帧）就停止解析该帧，
     * 已解析出的条目仍然有效（固件是按 500 字节预算分批的，正常不会截断）。
     */
    internal fun parseFileList(data: ByteArray): List<DeviceFile> {
        if (data.size < 2) return emptyList()
        val count = u8(data[1])
        var p = 2
        val out = ArrayList<DeviceFile>(count)
        repeat(count) {
            if (p + 1 > data.size) return out
            val nl = u8(data[p]); p += 1
            if (nl == 0 || p + nl + 3 > data.size) return out
            val name = String(data, p, nl, Charsets.UTF_8)
            p += nl
            val isDir = u8(data[p]) != 0; p += 1
            val size = u16be(data, p); p += 2
            out.add(DeviceFile(name, size, isDir))
        }
        return out
    }

    /** 0x97 帧：count [dirLen dir…]×count */
    internal fun parseDirsList(data: ByteArray): List<String> {
        if (data.size < 2) return emptyList()
        val count = u8(data[1])
        var p = 2
        val out = ArrayList<String>(count)
        repeat(count) {
            if (p + 1 > data.size) return out
            val dl = u8(data[p]); p += 1
            if (dl == 0 || p + dl > data.size) return out
            out.add(String(data, p, dl, Charsets.UTF_8))
            p += dl
        }
        return out
    }
}
