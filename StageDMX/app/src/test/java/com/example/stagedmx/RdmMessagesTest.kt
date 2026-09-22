package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RDM 三帧（0x89 扫描头 / 0x8A 设备 / 0x8B 操作结果）的解析测试。
 *
 * 为什么单独写这一个文件：这三帧原来是 MainActivity.onNotify 里**内联手写解析**的
 * （0x8A 还转手给 RdmStore.parseRdmDevice），是全工程唯一没进 [DeviceMessages]
 * 这一套纯函数解析器、也没有任何单测的帧。现在它们和别的帧共用同一套长度校验，
 * 顺便把边界补上。
 */
class RdmMessagesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 拼一条 0x89：count uni ok errLen err… */
    private fun scanHead(count: Int, uni: Int, ok: Int, err: String): ByteArray {
        val e = err.toByteArray(Charsets.UTF_8)
        return bytes(DmxProtocol.RESP_RDM_SCAN, count, uni, ok, e.size) + e
    }

    /** 拼一条 0x8B：ok errLen err… */
    private fun result(ok: Int, err: String): ByteArray {
        val e = err.toByteArray(Charsets.UTF_8)
        return bytes(DmxProtocol.RESP_RDM_RESULT, ok, e.size) + e
    }

    // ---------------- 0x89 扫描头 ----------------

    @Test
    fun `scan head parses count universe and ok`() {
        val m = DeviceMessages.parse(scanHead(6, 1, 0, "")) as Msg.RdmScanHead
        assertEquals(6, m.count)
        assertEquals(1, m.universe)
        assertTrue(m.ok)
        assertEquals("", m.err)
    }

    @Test
    fun `scan head carries the error text even when ok`() {
        // 固件在"扫描成功但 DMX 驱动没重装回来"时会带上 found>0 + 非空 err，
        // 这条路径以前 App 直接丢掉了 err（只当失败时才看）。
        val m = DeviceMessages.parse(scanHead(6, 1, 0, "驱动重装失败：本宇宙输出已停"))
            as Msg.RdmScanHead
        assertTrue(m.ok)
        assertTrue(m.err.contains("驱动重装失败"))
    }

    @Test
    fun `scan head rejects truncated frame`() {
        assertNull(DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_SCAN, 1, 1, 0)))          // 只有 4 字节
        assertNull(DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_SCAN)))                   // 只有命令
    }

    @Test
    fun `scan head clamps errLen to the real frame length`() {
        // 声明 200 字节错误文本，实际只有 3 字节 —— 不能越界读，也不能抛异常
        val m = DeviceMessages.parse(
            bytes(DmxProtocol.RESP_RDM_SCAN, 1, 1, 1, 200) + "abc".toByteArray()
        ) as Msg.RdmScanHead
        assertEquals("abc", m.err)
        assertFalse(m.ok)
    }

    @Test
    fun `scan head with zero errLen gives empty string`() {
        val m = DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_SCAN, 0, 1, 1, 0)) as Msg.RdmScanHead
        assertEquals("", m.err)
        assertFalse(m.ok)
    }

    // ---------------- 0x8B 操作结果 ----------------

    @Test
    fun `result ok has empty error`() {
        val m = DeviceMessages.parse(result(0, "")) as Msg.RdmResult
        assertTrue(m.ok)
        assertEquals("", m.err)
    }

    @Test
    fun `result failure carries reason`() {
        val m = DeviceMessages.parse(result(1, "改址失败：设备无应答")) as Msg.RdmResult
        assertFalse(m.ok)
        assertEquals("改址失败：设备无应答", m.err)
    }

    @Test
    fun `result rejects truncated frame`() {
        assertNull(DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_RESULT, 0)))
        assertNull(DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_RESULT)))
    }

    @Test
    fun `result clamps errLen`() {
        val m = DeviceMessages.parse(
            bytes(DmxProtocol.RESP_RDM_RESULT, 1, 99) + "xy".toByteArray()
        ) as Msg.RdmResult
        assertEquals("xy", m.err)
    }

    // ---------------- 0x8A 设备帧 ----------------

    /** 按固件 ble_dmx.c 的布局拼一条完整的 0x8A。 */
    private fun deviceFrame(
        uid: IntArray,
        addr: Int,
        footprint: Int,
        labels: List<String>,
        personalityNum: Int = 1,
        personalityCount: Int = 2,
    ): ByteArray {
        val head = ArrayList<Int>()
        head.add(DmxProtocol.RESP_RDM_DEVICE)
        head.add(0)                       // idx
        head.add(1)                       // universe(0=A)
        head.addAll(uid.toList())         // uid 6 字节
        head.add((addr ushr 8) and 0xFF); head.add(addr and 0xFF)
        head.add((footprint ushr 8) and 0xFF); head.add(footprint and 0xFF)
        head.add(0); head.add(0x12)       // model id
        head.add(0x01); head.add(0x04)    // product category
        head.addAll(listOf(0, 0, 0, 1))   // software version id
        head.add(personalityNum)
        head.add(personalityCount)
        head.add(0); head.add(0)          // sub devices
        head.add(0)                       // sensors
        var out = bytes(*head.toIntArray())
        for (s in labels) {
            val b = s.toByteArray(Charsets.UTF_8)
            out += bytes(b.size) + b
        }
        return out
    }

    @Test
    fun `device frame parses uid address and footprint`() {
        val raw = deviceFrame(
            intArrayOf(0x05, 0xE0, 0x0D, 0xE0, 0x00, 0x01), 141, 20,
            listOf("DEMO", "Demo Beam 12CH", "1.0", "光束1", "Standard"))
        val m = DeviceMessages.parse(raw) as Msg.RdmDeviceMsg
        assertEquals("05E0:0DE00001", m.device.uid)
        assertEquals(141, m.device.address)
        assertEquals(20, m.device.channelCount)
        assertEquals("Demo Beam 12CH", m.device.modelDesc)
        assertEquals("光束1", m.device.deviceLabel)
        assertEquals("Standard", m.device.personality)
    }

    @Test
    fun `device frame with unknown footprint stays zero`() {
        // 固件在 DEVICE_INFO 两次都没应答时会发 footprint=0（参数未知）。
        // App 侧必须原样保留 0 —— 以前 rdmFootprint() 会 coerceAtLeast(1) 把它
        // 悄悄当成 1 通道，于是这台幽灵占掉一个地址、把后面所有灯顶偏一格。
        val raw = deviceFrame(intArrayOf(5, 0xE0, 0, 0, 0, 1), 0, 0, listOf("", "", "", "", ""))
        val m = DeviceMessages.parse(raw) as Msg.RdmDeviceMsg
        assertEquals(0, m.device.address)
        assertEquals(0, m.device.channelCount)
    }

    @Test
    fun `device frame rejects short frame`() {
        assertNull(DeviceMessages.parse(bytes(DmxProtocol.RESP_RDM_DEVICE)))
        assertNull(DeviceMessages.parse(ByteArray(25) { if (it == 0) 0x8A.toByte() else 0 }))
    }

    @Test
    fun `device frame tolerates missing label segments`() {
        // 只来 2 段字符串（固件理论上发 5 段，但设备可能少支持几项）
        val raw = deviceFrame(intArrayOf(5, 0xE0, 0, 0, 0, 2), 10, 12, listOf("ACME", "Spot"))
        val m = DeviceMessages.parse(raw) as Msg.RdmDeviceMsg
        assertEquals("ACME", m.device.manufacturer)
        assertEquals("Spot", m.device.modelDesc)
        assertEquals("", m.device.personality)     // 缺失的段 → 空串，不越界
    }

    @Test
    fun `device frame tolerates label length beyond frame`() {
        // 声明某段长 200 字节，实际只剩 2 字节 —— 必须截断而不是越界读
        var raw = deviceFrame(intArrayOf(5, 0xE0, 0, 0, 0, 3), 1, 8, emptyList())
        raw += bytes(200) + "ok".toByteArray()
        val m = DeviceMessages.parse(raw) as Msg.RdmDeviceMsg
        assertEquals("ok", m.device.manufacturer)
    }
}
