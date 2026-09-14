package com.example.stagedmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InstanceForm] 的单元测试。
 *
 * 这层逻辑原先内联在 MainActivity 的"添加灯具实例"对话框里（且写了两份），
 * 负责决定"这个灯型在本宇宙放不放得下、共几台、落在哪个地址段"——
 * 正是错误提示最容易与预览不一致的地方，现在可以离线验证。
 */
class InstanceFormTest {

    private fun form(pitch: Int, addr: Int, count: Int, universe: Int = 1) =
        InstanceForm(pitch, addr, count, universe)

    // ---------------- 频道布局算术 ----------------

    @Test
    fun `single fixture lands at its start address`() {
        val f = form(pitch = 16, addr = 1, count = 1)
        assertEquals(listOf(1), f.plan)
        assertEquals(16, f.lastChannel)
        assertTrue(f.fits)
    }

    @Test
    fun `instances are laid out at pitch intervals`() {
        // 20ch 灯型，从 1 起放 3 台 → 1 / 21 / 41，末通道 60
        val f = form(pitch = 20, addr = 1, count = 3)
        assertEquals(listOf(1, 21, 41), f.plan)
        assertEquals(60, f.lastChannel)
        assertTrue(f.fits)
    }

    @Test
    fun `layout starts from the requested address`() {
        val f = form(pitch = 10, addr = 100, count = 3)
        assertEquals(listOf(100, 110, 120), f.plan)
        assertEquals(129, f.lastChannel)
    }

    // ---------------- 边界：放不下 ----------------

    @Test
    fun `overflowing the universe is detected`() {
        // 20ch × 26 台 = 520 > 512 → 放不下
        val f = form(pitch = 20, addr = 1, count = 26)
        assertFalse(f.fits)
        assertTrue(f.summaryText().contains("放不下"))
    }

    @Test
    fun `exactly filling the universe fits`() {
        // 512 通道的灯型 1 台正好占满
        val f = form(pitch = 512, addr = 1, count = 1)
        assertEquals(512, f.lastChannel)
        assertTrue(f.fits)
    }

    @Test
    fun `last instance ending exactly at 512 fits`() {
        // 16ch × 32 台 = 512
        val f = form(pitch = 16, addr = 1, count = 32)
        assertEquals(512, f.lastChannel)
        assertTrue(f.fits)
    }

    @Test
    fun `one channel over the universe does not fit`() {
        // 16ch × 32 台但从 2 起 → 末台结束于 513
        val f = form(pitch = 16, addr = 2, count = 32)
        assertEquals(513, f.lastChannel)
        assertFalse(f.fits)
    }

    @Test
    fun `fixture wider than a universe is rejected outright`() {
        val f = form(pitch = 600, addr = 1, count = 1)
        assertTrue(f.fixtureTooWide)
        assertEquals(emptyList<Int>(), f.plan)
        assertEquals(0, f.lastChannel)
        assertFalse(f.fits)
        assertTrue(f.summaryText().contains("无法 patch"))
        assertNull(f.addedToast("X"))
    }

    // ---------------- 参数夹取 ----------------

    @Test
    fun `address is clamped into the universe`() {
        assertEquals(1, form(16, 0, 1).addr)
        assertEquals(1, form(16, -5, 1).addr)
        assertEquals(512, form(16, 9999, 1).addr)
    }

    @Test
    fun `count is clamped to the batch limit`() {
        assertEquals(1, form(16, 1, 0).numInstances)
        assertEquals(1, form(16, 1, -3).numInstances)
        assertEquals(MAX_BATCH_ADD, form(1, 1, 9999).numInstances)
    }

    @Test
    fun `zero pitch is treated as one channel`() {
        val f = form(pitch = 0, addr = 1, count = 3)
        assertEquals(1, f.pitch)
        assertEquals(listOf(1, 2, 3), f.plan)
    }

    // ---------------- 宇宙 / 波段 ----------------

    @Test
    fun `universe maps to band letter`() {
        assertEquals("A", form(16, 1, 1, universe = 1).band)
        assertEquals("B", form(16, 1, 1, universe = 2).band)
        // 越界宇宙夹到合法范围
        assertEquals("A", form(16, 1, 1, universe = 0).band)
        assertEquals("B", form(16, 1, 1, universe = 9).band)
    }

    @Test
    fun `summary mentions the band`() {
        assertTrue(form(16, 1, 1, universe = 2).summaryText().startsWith("B 通道"))
        assertTrue(form(16, 1, 3, universe = 2).summaryText().contains("B 通道"))
    }

    // ---------------- 文案与一致性 ----------------

    @Test
    fun `single instance summary has no count prefix`() {
        val s = form(20, 1, 1).summaryText()
        assertEquals("A 通道 1 ~ 20", s)
    }

    @Test
    fun `multi instance summary reports count and last start`() {
        val s = form(20, 1, 3).summaryText()
        assertTrue(s.contains("共 3 台"))
        assertTrue(s.contains("1 ~ 60"))
        assertTrue(s.contains("第 3 台起于 41"))
    }

    /** 预览文案里的地址段必须与 plan 的实际落位完全一致（旧的重复实现易在此漂移）。 */
    @Test
    fun `summary addresses always match the plan`() {
        for (pitch in listOf(1, 8, 16, 20, 24, 34, 512)) {
            for (count in listOf(1, 2, 5, 32)) {
                val f = form(pitch, 1, count)
                if (!f.fits) continue
                val s = f.summaryText()
                assertTrue("pitch=$pitch count=$count → $s", s.contains(f.plan.first().toString()))
                assertTrue("pitch=$pitch count=$count → $s", s.contains(f.lastChannel.toString()))
            }
        }
    }

    @Test
    fun `added toast reflects the layout`() {
        assertEquals("已添加 PAR-1 A@1", form(20, 1, 1).addedToast("PAR"))
        val multi = form(20, 1, 3).addedToast("PAR")!!
        assertTrue(multi.contains("已添加 3 台"))
        assertTrue(multi.contains("1~60"))
    }

    @Test
    fun `fixture hint repeats the pitch`() {
        assertTrue(form(24, 1, 1).fixtureHintText().contains("24"))
    }
}
