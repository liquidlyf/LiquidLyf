package io.github.liquidlyf.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** L10N-01: "+88 01700-000000", the way people in Bangladesh write their numbers. */
class PhoneDisplayTest {
    @Test
    fun `a Bangladeshi mobile number keeps the +88 and groups the local digits`() {
        assertEquals("+88 01700\u2011000000", PhoneDisplay.format("+8801700000000"))
        assertEquals("+88 01812\u2011345678", PhoneDisplay.format("+8801812345678"))
    }

    @Test
    fun `Bangla screens get Bangla digits`() {
        assertEquals("+৮৮ ০১৮১২\u2011৩৪৫৬৭৮", PhoneDisplay.format("+8801812345678", banglaDigits = true))
    }

    @Test
    fun `TalkBack gets every digit on its own, in the groups people see`() {
        assertEquals("+8 8, 0 1 8 1 2, 3 4 5 6 7 8", PhoneDisplay.spoken("+8801812345678"))
        assertEquals("+4 4 7 7 0 0 9 0 0 1 2 3", PhoneDisplay.spoken("+447700900123"))
        assertEquals("0 1 7 0 0 0 0 0 0 0 0", PhoneDisplay.spoken("01700000000"))
    }

    @Test
    fun `anything that isn't a stored Bangladeshi mobile number is shown as it is`() {
        assertEquals("+447700900123", PhoneDisplay.format("+447700900123"))
        assertEquals("01700000000", PhoneDisplay.format("01700000000"))
    }
}
