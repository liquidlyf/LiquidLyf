package io.github.liquidlyf.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/** A34 item 7 = b: Bangla times say the part of the day instead of AM/PM. */
class BanglaDayPartTest {
    @Test
    fun `every hour has its part of the day`() {
        val parts = (0..23).map(::banglaDayPart)
        assertEquals(List(4) { "রাত" }, parts.subList(0, 4))
        assertEquals(List(2) { "ভোর" }, parts.subList(4, 6))
        assertEquals(List(6) { "সকাল" }, parts.subList(6, 12))
        assertEquals(List(3) { "দুপুর" }, parts.subList(12, 15))
        assertEquals(List(3) { "বিকেল" }, parts.subList(15, 18)) // 15:22 = "বিকেল ৩:২২" (times = b)
        assertEquals(List(2) { "সন্ধ্যা" }, parts.subList(18, 20))
        assertEquals(List(4) { "রাত" }, parts.subList(20, 24))
    }
}
