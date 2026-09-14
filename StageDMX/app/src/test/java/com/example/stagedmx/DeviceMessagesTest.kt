package com.example.stagedmx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeviceMessages.parse] 的单元测试 —— 纯 JVM，不需要真机/蓝牙。
 *
 * 这是把协议解析从 MainActivity 抽出来的主要收益：原来这段 11 分支的二进制解析
 * 完全无法验证，而它同时是"越界读"和"现场功能失效"两类问题的高发区。
 */
class DeviceMessagesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---------------- 通用：非法/未知帧 ----------------

    @Test
    fun `empty frame is rejected`() {
        assertNull(DeviceMessages.parse(ByteArray(0)))
    }

    @Test
    fun `unknown command is rejected`() {
        assertNull(DeviceMessages.parse(bytes(0x7F, 1, 2, 3)))
    }

    /** 每个已知命令的"刚好差一字节"都必须被拒绝，而不是越界读。 */
    @Test
    fun `truncated frames are rejected`() {
        val heads = listOf(
            DmxProtocol.RESP_STATE_HEAD, DmxProtocol.RESP_STATE_CHUNK,
            DmxProtocol.RESP_STATE_FX, DmxProtocol.RESP_UPLOAD_RESULT,
            DmxProtocol.RESP_FILE_LIST, DmxProtocol.RESP_FILE_CHUNK,
            DmxProtocol.RESP_FILE_END, DmxProtocol.RESP_DELETE_RESULT,
            DmxProtocol.RESP_DIR_RESULT, DmxProtocol.RESP_DIRS_LIST, DmxProtocol.RESP_DIRS_END
        )
        for (h in heads) {
            // 只有命令字节：长度不足，必须 null（0x85/0x98 是单字节帧，单独处理）
            if (h == DmxProtocol.RESP_STATE_END || h == DmxProtocol.RESP_DIRS_END) continue
            assertNull("cmd=0x${h.toString(16)} 仅 1 字节应被拒绝", DeviceMessages.parse(bytes(h)))
        }
    }

    @Test
    fun `single byte end frames are accepted`() {
        assertEquals(Msg.StateEnd, DeviceMessages.parse(bytes(DmxProtocol.RESP_STATE_END)))
        assertEquals(Msg.DirsEnd, DeviceMessages.parse(bytes(DmxProtocol.RESP_DIRS_END)))
    }

    // ---------------- 0x82 状态头 ----------------

    @Test
    fun `state head parses uptime big endian`() {
        // uptime = 0x01020304 = 16909060 秒
        val m = DeviceMessages.parse(
            bytes(0x82, 0x00, 0x01, 0x02, 0x03, 0x04, 3, 0b0000_0101)
        )
        assertTrue(m is Msg.StateHead)
        m as Msg.StateHead
        assertEquals(16909060L, m.uptimeSec)
        assertEquals(3, m.fxCount)
        assertEquals(0b101, m.progMask)
    }

    @Test
    fun `state head uptime uses full 32 bits unsigned`() {
        // 高位置 1 时不能变成负数（曾用 Int 拼接会溢出）
        val m = DeviceMessages.parse(bytes(0x82, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0))
        m as Msg.StateHead
        assertEquals(4294967295L, m.uptimeSec)
    }

    // ---------------- 0x83 通道分块 ----------------

    @Test
    fun `state chunk parses start and values`() {
        val m = DeviceMessages.parse(bytes(0x83, 1, 0x02, 0x00, 3, 10, 20, 30))
        m as Msg.StateChunk
        assertEquals(512, m.start)
        assertArrayEquals(intArrayOf(10, 20, 30), m.values)
    }

    /** count 声称 3 个值但只跟了 2 个 → 必须拒绝，不能读越界。 */
    @Test
    fun `state chunk with lying count is rejected`() {
        assertNull(DeviceMessages.parse(bytes(0x83, 1, 0x00, 0x01, 3, 10, 20)))
    }

    @Test
    fun `state chunk with zero start is rejected`() {
        assertNull(DeviceMessages.parse(bytes(0x83, 1, 0x00, 0x00, 1, 10)))
    }

    @Test
    fun `state chunk with zero count is rejected`() {
        assertNull(DeviceMessages.parse(bytes(0x83, 1, 0x00, 0x01, 0, 10)))
    }

    // ---------------- 0x92 文件列表（本次重构重点修复的越界点） ----------------

    @Test
    fun `file list parses name type and size`() {
        val name = "a.xml".toByteArray()
        val frame = ByteArray(2 + 1 + name.size + 1 + 2)
        frame[0] = 0x92.toByte()
        frame[1] = 1               // count
        frame[2] = name.size.toByte()
        System.arraycopy(name, 0, frame, 3, name.size)
        frame[3 + name.size] = 0   // 文件
        frame[4 + name.size] = 0x01
        frame[5 + name.size] = 0x2C  // size = 300

        val m = DeviceMessages.parse(frame)
        m as Msg.FileList
        assertEquals(1, m.files.size)
        assertEquals("a.xml", m.files[0].name)
        assertEquals(300, m.files[0].size)
        assertTrue(!m.files[0].isDir)
    }

    @Test
    fun `file list marks directories`() {
        val name = "lib".toByteArray()
        val frame = ByteArray(2 + 1 + name.size + 1 + 2)
        frame[0] = 0x92.toByte(); frame[1] = 1
        frame[2] = name.size.toByte()
        System.arraycopy(name, 0, frame, 3, name.size)
        frame[3 + name.size] = 1   // 目录

        val m = DeviceMessages.parse(frame)
        m as Msg.FileList
        assertTrue(m.files[0].isDir)
    }

    /**
     * 回归：旧实现只校验 `p + nl + 3 > data.size`，name 之后读 type/size 时可能越界。
     * name 长度声明到帧尾、后面没有 type/size 字节 → 必须拒绝该条而不是崩溃。
     */
    @Test
    fun `file list entry without trailing type and size is dropped`() {
        // count=1, nameLen=4, 只有 4 个 name 字节，无 type/size
        val frame = bytes(0x92, 1, 4, 'a'.code, 'b'.code, 'c'.code, 'd'.code)
        val m = DeviceMessages.parse(frame)
        m as Msg.FileList
        assertTrue("越界条目应被丢弃", m.files.isEmpty())
    }

    @Test
    fun `file list with more entries than bytes parses only what fits`() {
        // count 声明 5 条，实际只够 1 条完整记录
        val frame = bytes(0x92, 5, 1, 'x'.code, 0, 0, 1)
        val m = DeviceMessages.parse(frame)
        m as Msg.FileList
        assertEquals(1, m.files.size)
        assertEquals("x", m.files[0].name)
    }

    @Test
    fun `empty file list is valid`() {
        val m = DeviceMessages.parse(bytes(0x92, 0))
        m as Msg.FileList
        assertTrue(m.files.isEmpty())
    }

    // ---------------- 0x97 目录列表 ----------------

    @Test
    fun `dirs list parses paths`() {
        val a = "x".toByteArray(); val b = "x/y".toByteArray()
        val frame = ByteArray(2 + 1 + a.size + 1 + b.size)
        frame[0] = 0x97.toByte(); frame[1] = 2
        var p = 2
        frame[p++] = a.size.toByte(); System.arraycopy(a, 0, frame, p, a.size); p += a.size
        frame[p++] = b.size.toByte(); System.arraycopy(b, 0, frame, p, b.size)

        val m = DeviceMessages.parse(frame)
        m as Msg.DirsList
        assertEquals(listOf("x", "x/y"), m.dirs)
    }

    @Test
    fun `dirs list entry running past end is dropped`() {
        // dirLen=10 但只有 2 字节
        assertTrue((DeviceMessages.parse(bytes(0x97, 1, 10, 'a'.code, 'b'.code))
            as Msg.DirsList).dirs.isEmpty())
    }

    // ---------------- 0x93 下载块 ----------------

    @Test
    fun `file chunk parses payload`() {
        val m = DeviceMessages.parse(bytes(0x93, 7, 3, 2, 0xAA, 0xBB))
        m as Msg.FileChunk
        assertEquals(7, m.seq)
        assertEquals(3, m.totalChunks)
        assertArrayEquals(bytes(0xAA, 0xBB), m.data)
    }

    @Test
    fun `file chunk with lying dataLen is rejected`() {
        assertNull(DeviceMessages.parse(bytes(0x93, 0, 1, 5, 0xAA)))
    }

    @Test
    fun `file chunk with zero payload is valid`() {
        val m = DeviceMessages.parse(bytes(0x93, 0, 1, 0))
        m as Msg.FileChunk
        assertEquals(0, m.data.size)
    }

    // ---------------- 状态字节：0 表示成功 ----------------

    @Test
    fun `status byte zero means success`() {
        assertTrue((DeviceMessages.parse(bytes(0x91, 0)) as Msg.UploadResult).ok)
        assertTrue((DeviceMessages.parse(bytes(0x94, 0)) as Msg.FileEnd).ok)
        assertTrue((DeviceMessages.parse(bytes(0x95, 0)) as Msg.DeleteResult).ok)
        assertTrue((DeviceMessages.parse(bytes(0x96, 0)) as Msg.DirResult).ok)
    }

    @Test
    fun `status byte non zero means failure`() {
        assertTrue(!(DeviceMessages.parse(bytes(0x91, 1)) as Msg.UploadResult).ok)
        assertTrue(!(DeviceMessages.parse(bytes(0x94, 1)) as Msg.FileEnd).ok)
        assertTrue(!(DeviceMessages.parse(bytes(0x95, 1)) as Msg.DeleteResult).ok)
        assertTrue(!(DeviceMessages.parse(bytes(0x96, 1)) as Msg.DirResult).ok)
    }

    /** 状态字节 >= 0x80 时必须按无符号解释（旧代码 `data[1].toInt()` 会得到负数）。 */
    @Test
    fun `status byte is read unsigned`() {
        assertTrue(!(DeviceMessages.parse(bytes(0x91, 0x80)) as Msg.UploadResult).ok)
    }
}
