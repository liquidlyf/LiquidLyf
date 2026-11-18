package io.github.liquidlyf.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ValidatorsTest {

    private val now = Instant.parse("2026-11-13T06:00:00Z")

    @Test
    fun `phone numbers people type become E164`() {
        val expected = "+8801712345678"
        listOf(
            "01712345678",
            "+8801712345678",
            "8801712345678",
            "008801712345678",
            "1712345678",
            "+880 1712-345678",
            "017 1234 5678",
            "(017) 12-345-678",
            "+880 01712345678",
            "০১৭১২৩৪৫৬৭৮", // Bangla digits
            "+৮৮০১৭১২৩৪৫৬৭৮",
            "  01712345678  ",
        ).forEach { assertEquals(it, expected, Validators.normalizePhone(it)) }
    }

    @Test
    fun `every mobile operator prefix 013 to 019 is accepted`() {
        for (p in 3..9) assertEquals("+8801${p}12345678", Validators.normalizePhone("01${p}12345678"))
    }

    @Test
    fun `landlines, wrong lengths and junk are rejected`() {
        listOf(
            "01212345678", // 012 isn't a mobile prefix
            "01112345678",
            "0171234567", // too short
            "017123456789", // too long
            "+9101712345678", // another country
            "02-9123456", // Dhaka landline
            "",
            "phone",
            "+880",
            "8801712345678 ext 2",
        ).forEach { assertNull(it, Validators.normalizePhone(it)) }
    }

    @Test
    fun `isBdPhone matches the rules pattern exactly`() {
        assertTrue(Validators.isBdPhone("+8801912345678"))
        assertFalse(Validators.isBdPhone("01912345678"))
        assertFalse(Validators.isBdPhone("+880191234567"))
        assertFalse(Validators.isBdPhone("+8801212345678"))
    }

    @Test
    fun `names are trimmed, collapsed and 2 to 60 chars`() {
        assertEquals("Rahim Uddin", Validators.normalizeName("  Rahim   Uddin "))
        assertTrue(Validators.isValidName("Al"))
        assertFalse(Validators.isValidName(" A "))
        assertTrue(Validators.isValidName("a".repeat(60)))
        assertFalse(Validators.isValidName("a".repeat(61)))
    }

    @Test
    fun `display name is first name and last initial`() {
        assertEquals("Rahim K.", Validators.displayName("Rahim Karim"))
        assertEquals("rahim K.", Validators.displayName("rahim abdul karim"))
        assertEquals("Nusrat", Validators.displayName("  Nusrat "))
        assertEquals("রহিম ক.", Validators.displayName("রহিম করিম"))
        assertEquals("", Validators.displayName("   "))
    }

    @Test
    fun `display name skips Md, Mst, Dr and other abbreviations`() {
        assertEquals("Abdul K.", Validators.displayName("Md. Abdul Karim"))
        assertEquals("Abdul K.", Validators.displayName("MD Abdul Karim"))
        assertEquals("Fatema B.", Validators.displayName("Mst. Fatema Begum"))
        assertEquals("Fazlul H.", Validators.displayName("A. K. M. Fazlul Haque"))
        assertEquals("রহিম উ.", Validators.displayName("মোঃ রহিম উদ্দিন"))
        assertEquals("Karim", Validators.displayName("Dr. Karim"))
        assertEquals("Md.", Validators.displayName("Md."))
        // Never shorter than the rules' 2 chars.
        assertEquals("Md. X.", Validators.displayName("Md. X"))
        assertEquals("Dr. X.", Validators.displayName("Dr. X"))
    }

    @Test
    fun `display name stays within 40 chars`() {
        val long = Validators.displayName("${"a".repeat(50)} Karim")
        assertEquals(Validators.DISPLAY_NAME_MAX, long.length)
        assertTrue(long.endsWith(" K."))
        assertEquals(40, Validators.displayName("b".repeat(60)).length)
    }

    @Test
    fun `money words match the rules list in English and Bangla, any case`() {
        listOf(
            "Will pay 500 taka", "BKASH me", "send via Nagad", "Rocket number", "bikash",
            "payment after", "৫০০ টাকা দেব", "বিকাশ করুন", "নগদ", "রকেট",
            "line one\nthen bKash",
        ).forEach { assertTrue(it, Validators.hasMoneyWords(it)) }
        listOf(null, "", "Need 2 bags urgently", "ICU bed 4", "রক্ত দরকার").forEach {
            assertFalse(it.toString(), Validators.hasMoneyWords(it))
        }
    }

    @Test
    fun `a short name leaves out a note in brackets and stray punctuation (VT-18)`() {
        assertEquals("Demo R.", Validators.displayName("Demo Rahim (brother)"))
        assertEquals("Rahim", Validators.displayName("Rahim (brother"))
        assertEquals("Karim H.", Validators.displayName("Karim Hossain,"))
        assertEquals("রহিমা খ.", Validators.displayName("রহিমা খাতুন (মা)"))
        assertEquals("Karim H.", Validators.displayName("Md. Karim Hossain"))
    }

    @Test
    fun `a mobile number in a note is found however it's written (VT-10)`() {
        listOf(
            "Call Rafiq 01712345678",
            "Rafiq: 01712-345678",
            "+88 01712 345 678 please",
            "8801712345678",
            "রফিক ০১৭১২৩৪৫৬৭৮",
            "রফিক +৮৮ ০১৭১২-৩৪৫৬৭৮",
        ).forEach { assertTrue(it, Validators.hasPhoneNumber(it)) }
        listOf(
            null, "", "Need 2 bags by 4 pm", "Ward 5, bed 12", "Operation on 20/11/2026",
            "Patient ID 1234567890123", "0171234567", "02-9665566",
        ).forEach { assertFalse(it.toString(), Validators.hasPhoneNumber(it)) }
    }

    @Test
    fun `birth year gives a profile age of 18 to 100`() {
        assertFalse(Validators.isValidBirthYear(2009, now))
        assertTrue(Validators.isValidBirthYear(2008, now))
        assertTrue(Validators.isValidBirthYear(1926, now))
        assertFalse(Validators.isValidBirthYear(1925, now))
    }

    @Test
    fun `needed by is at least 15 minutes ahead and under 30 days`() {
        assertFalse(Validators.isValidNeededBy(now, now))
        assertFalse(Validators.isValidNeededBy(now.plus(Duration.ofMinutes(14)), now))
        assertTrue(Validators.isValidNeededBy(now.plus(Duration.ofMinutes(15)), now))
        assertTrue(Validators.isValidNeededBy(now.plus(Duration.ofDays(29)), now))
        assertFalse(Validators.isValidNeededBy(now.plus(Duration.ofDays(30)), now))
    }

    @Test
    fun `expiry is exactly 24 hours after needed by`() {
        assertEquals(Instant.parse("2026-11-14T06:00:00Z"), Validators.expiresAt(now))
    }

    @Test
    fun `units, optional text and hospital names follow the rules limits`() {
        assertFalse(Validators.isValidUnits(0))
        assertTrue(Validators.isValidUnits(1))
        assertTrue(Validators.isValidUnits(10))
        assertFalse(Validators.isValidUnits(11))

        assertTrue(Validators.fitsOptional(null, Validators.NOTES_MAX))
        assertTrue(Validators.fitsOptional("x".repeat(500), Validators.NOTES_MAX))
        assertFalse(Validators.fitsOptional("x".repeat(501), Validators.NOTES_MAX))

        assertTrue(Validators.isValidHospitalName("DMCH"))
        assertFalse(Validators.isValidHospitalName(" D "))
        assertFalse(Validators.isValidHospitalName("x".repeat(121)))
    }

    @Test
    fun `emails need a name, an at sign and a dotted domain`() {
        listOf("rahim@example.com", " rahim.uddin+bd@mail.example.org ", "a@b.co").forEach { assertTrue(it, Validators.isValidEmail(it)) }
        listOf("", "rahim", "rahim@", "@example.com", "rahim@example", "rahim @example.com", "rahim@example..com", "a@b.")
            .forEach { assertFalse(it, Validators.isValidEmail(it)) }
    }

    @Test
    fun `codes and ids allow only path- and topic-safe characters`() {
        assertTrue(Validators.isCode("dhaka-north_12"))
        assertFalse(Validators.isCode(null))
        assertFalse(Validators.isCode(""))
        assertFalse(Validators.isCode("a.b"))
        assertFalse(Validators.isCode("a/b"))
        assertFalse(Validators.isCode("x".repeat(41)))
        assertTrue(Validators.isId("x".repeat(128)))
        assertFalse(Validators.isId("x".repeat(129)))
        assertFalse(Validators.isId("uid with space"))
    }
}
