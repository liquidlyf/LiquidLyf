package io.github.liquidlyf.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SafeIntentsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        // Bangla formats numbers with Bangla digits by default; URIs must never contain them.
        Locale.setDefault(Locale.forLanguageTag("bn-BD"))
    }

    @After
    fun tearDown() = Locale.setDefault(originalLocale)

    @Test
    fun `tel uri keeps only digits and plus`() {
        assertEquals("tel:+8801712345678", SafeIntents.telUri("+880 1712-345678"))
        assertEquals("tel:01712345678", SafeIntents.telUri("01712 345 678"))
    }

    @Test
    fun `mailto uri encodes the subject`() {
        assertEquals(
            "mailto:liquidlyf@gmail.com?subject=My%20account%20%26%20you",
            SafeIntents.mailtoUri(" liquidlyf@gmail.com ", "My account & you"),
        )
    }

    @Test
    fun `geo uri uses ascii digits and encodes the label`() {
        assertEquals(
            "geo:23.810332,90.412518?q=23.810332,90.412518(Dhaka%20Medical%20College)",
            SafeIntents.geoUri(23.810332, 90.412518, "Dhaka Medical College"),
        )
        assertEquals("geo:23.000000,90.000000?q=23.000000,90.000000", SafeIntents.geoUri(23.0, 90.0, null))
    }

    @Test
    fun `maps web url needs no api key`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=23.810332,90.412518",
            SafeIntents.mapsWebUrl(23.810332, 90.412518),
        )
    }

    @Test
    fun `only http and https links are opened`() {
        assertTrue(SafeIntents.isWebUrl("https://liquidlyf.github.io/privacy"))
        assertTrue(SafeIntents.isWebUrl(" HTTP://example.org "))
        assertFalse(SafeIntents.isWebUrl("https://"))
        assertFalse(SafeIntents.isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(SafeIntents.isWebUrl("javascript:alert(1)"))
        assertFalse(SafeIntents.isWebUrl("file:///sdcard/x"))
        assertFalse(SafeIntents.isWebUrl(""))
    }
}
