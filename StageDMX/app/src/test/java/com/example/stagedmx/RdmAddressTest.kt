package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RDM 排址的单元测试。
 *
 * 为什么要专门测：这段代码会**真的把地址写进灯里**，写错的后果是现场一堆灯串到
 * 同一段地址上（比"界面显示不对"严重得多）。而它以前一条测试都没有 ——
 * 加上界面上"显示的起始地址"和"真正排出来的地址"过去是**两份各算一遍的实现**，
 * 靠注释里一句"两处必须同源"维持，没有东西能发现它们漂了。
 *
 * 所以这里除了测算法本身，还专门测 [groupStartsOf] 与
 * [assignAddressesByGroup] **必须给出同一套组起始地址**。
 *
 * ⚠ 这两个函数是 RdmStore.kt 里的**顶层函数**（不在 `class RdmStore` 内），
 *   所以不能写成 `RdmStore.xxx`。
 */
class RdmAddressTest {

    private fun dev(uid: String, model: String, ch: Int, addr: Int = 0) =
        RdmDevice(uid = uid, manufacturer = "OMAR", model = model,
            address = addr, channelCount = ch)

    /** 10 台灯分两组：A 组 3 台 20ch、B 组 2 台 12ch。 */
    private val groupA = listOf(
        dev("A1", "M20", 20), dev("A2", "M20", 20), dev("A3", "M20", 20))
    private val groupB = listOf(dev("B1", "M12", 12), dev("B2", "M12", 12))

    private fun byModel(d: RdmDevice) = d.model

    // ---------------- 基本顺序 ----------------

    @Test
    fun `groups are laid out back to back from one`() {
        val plan = assignAddressesByGroup(groupA + groupB, ::byModel, emptyMap())!!
        assertEquals(listOf("A1" to 1, "A2" to 21, "A3" to 41, "B1" to 61, "B2" to 73),
            plan.map { it.first.uid to it.second })
    }

    @Test
    fun `a group with an explicit start keeps it and pushes the next group`() {
        val plan = assignAddressesByGroup(
            groupA + groupB, ::byModel, mapOf("M12" to 200))!!
        assertEquals(200, plan.first { it.first.uid == "B1" }.second)
        assertEquals(212, plan.first { it.first.uid == "B2" }.second)
        // A 组不受影响
        assertEquals(1, plan.first { it.first.uid == "A1" }.second)
    }

    @Test
    fun `unknown footprint devices do not占 address space`() {
        // 中间插一台参数未知（ch=0）的灯：它不该把后面两组顶偏
        val broken = dev("X", "MX", 0)
        val plan = assignAddressesByGroup(
            groupA + listOf(broken) + groupB, ::byModel, emptyMap())!!
        assertTrue("参数未知的灯不该出现在排址结果里", plan.none { it.first.uid == "X" })
        assertEquals(1, plan.first { it.first.uid == "A1" }.second)
        assertEquals("B 组应当紧接 A 组，不被未知灯顶偏",
            61, plan.first { it.first.uid == "B1" }.second)
    }

    @Test
    fun `all unknown devices yield an empty plan not null`() {
        val plan = assignAddressesByGroup(
            listOf(dev("X", "MX", 0)), ::byModel, emptyMap())
        assertEquals(emptyList<Pair<RdmDevice, Int>>(), plan)
    }

    // ---------------- "相同地址"模式 ----------------

    @Test
    fun `same-address group occupies only one fixture width`() {
        // A 组设为"整组同一地址" → 3 台都指向 1，且整组只占 20 个通道
        val plan = assignAddressesByGroup(
            groupA + groupB, ::byModel, emptyMap(),
            footprintOf = { it.channelCount }, sameOf = { it == "M20" })!!
        assertEquals(listOf(1, 1, 1), plan.filter { it.first.uid.startsWith("A") }.map { it.second })
        assertEquals("B 组应当紧接在 21（而不是 61）",
            21, plan.first { it.first.uid == "B1" }.second)
    }

    // ---------------- 越界 ----------------

    @Test
    fun `overflow returns null`() {
        val huge = listOf(dev("H1", "MH", 300), dev("H2", "MH", 300))
        assertNull("两台 300ch 放不进一个宇宙，必须返回 null（调用方据此提示）",
            assignAddressesByGroup(huge, ::byModel, emptyMap()))
        // 边界：正好塞满 512 是可以的
        val exact = listOf(dev("E1", "ME", 300), dev("E2", "ME", 212))
        val plan = assignAddressesByGroup(exact, ::byModel, emptyMap())!!
        assertEquals(512, plan.last().second + 212 - 1)
    }

    // ---------------- 两条路径必须同源（这次的真正目的）----------------

    @Test
    fun `displayed group starts match the ones actually used for addressing`() {
        val cases = listOf(
            Triple(groupA + groupB, emptyMap<String, Int>(), false),
            Triple(groupA + groupB, mapOf("M12" to 200), false),
            Triple(groupA + groupB + listOf(dev("X", "MX", 0)), emptyMap<String, Int>(), false),
            Triple(groupA + groupB, emptyMap<String, Int>(), true),
            Triple(groupA + groupB, mapOf("M20" to 400), true),
        )
        for ((devices, starts, same) in cases) {
            val shown = groupStartsOf(
                devices, ::byModel, starts,
                footprintOf = { it.channelCount }, sameOf = { same && it == "M20" })
            val plan = assignAddressesByGroup(
                devices, ::byModel, starts,
                footprintOf = { it.channelCount }, sameOf = { same && it == "M20" })!!

            // 每台灯实际拿到的地址，必须落在"界面上显示的它那组的起始地址"上
            for ((d, actual) in plan) {
                val g = byModel(d)
                val shownStart = shown[g]
                assertTrue("组 $g 的起始地址没显示出来（starts=$starts same=$same）", shownStart != null)
                if (same && g == "M20") {
                    assertEquals("相同地址模式下整组都该等于显示的起始",
                        shownStart, actual)
                } else {
                    assertTrue("组 $g：实际地址 $actual 早于显示的起始 $shownStart",
                        actual >= shownStart!!)
                }
            }
        }
    }

    @Test
    fun `group starts are back to back and start from one`() {
        val shown = groupStartsOf(groupA + groupB, ::byModel, emptyMap())
        assertEquals(linkedMapOf("M20" to 1, "M12" to 61), shown)
    }

    @Test
    fun `explicit start is used and following group follows it`() {
        val shown = groupStartsOf(
            groupA + groupB, ::byModel, mapOf("M12" to 200))
        assertEquals(1, shown["M20"])
        assertEquals(200, shown["M12"])
    }

    @Test
    fun `start is clamped into the universe`() {
        val shown = groupStartsOf(
            groupA, ::byModel, mapOf("M20" to 9999))
        assertEquals(DmxProtocol.UNIVERSE_SIZE, shown["M20"])
        val zero = groupStartsOf(groupA, ::byModel, mapOf("M20" to 0))
        assertEquals(1, zero["M20"])
    }

    @Test
    fun `unknown-device group still appears in the display map`() {
        // 参数未知的灯也要在界面上有一行，它的组起始地址不能缺
        val shown = groupStartsOf(
            groupA + listOf(dev("X", "MX", 0)), ::byModel, emptyMap())
        assertTrue("未知灯所属的组也要有起始地址", shown.containsKey("MX"))
        assertEquals(1, shown["M20"])
    }
}
