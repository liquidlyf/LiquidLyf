package io.github.liquidlyf.core.data.hospital

import io.github.liquidlyf.core.model.PlaceName
import org.junit.Assert.assertEquals
import org.junit.Test

class HospitalSearchTest {

    // য় written as one character (U+09DF), and as য plus a nukta (U+09AF U+09BC).
    private val yaOne = "য়"
    private val yaTwo = "য়"

    private fun hospital(id: String, en: String, bn: String?, district: String = "BD3067", upazila: String? = null) =
        Hospital(id, PlaceName(en, bn), address = null, district = district, upazila = upazila, location = null)

    private val all = listOf(
        hospital("ng", "Narayanganj General Hospital", "নারা${yaOne}ণগঞ্জ জেনারেল হাসপাতাল"),
        hospital("ct", "City Hospital", "সিটি হাসপাতাল, নারা${yaTwo}ণগঞ্জ"),
        hospital("dh", "Dhaka Medical College Hospital", "ঢাকা মেডিকেল কলেজ হাসপাতাল", district = "BD3026"),
    )

    private fun ids(query: String) = HospitalSearch.search(all, "BD3067", query, limit = 8).map { it.id }

    @Test
    fun `either form of a Bangla letter finds both forms, names starting with it first`() {
        // "ng" starts with the query and "ct" only contains it, so "ng" leads although "City" sorts first.
        assertEquals(listOf("ng", "ct"), ids("নারা${yaTwo}ণগঞ্জ"))
        assertEquals(listOf("ng", "ct"), ids("নারা${yaOne}ণগঞ্জ"))
    }

    @Test
    fun `ignores case and stays in the district, and blank lists the district`() {
        assertEquals(listOf("ct", "ng"), ids("HOSPITAL"))
        assertEquals(listOf("ct"), ids("city"))
        assertEquals(emptyList<String>(), ids("dhaka"))
        assertEquals("FORM-02: the list opens before typing", listOf("ct", "ng"), ids("  "))
    }

    @Test
    fun `hospitals in the request's upazila come first`() {
        val near = all + hospital("kh", "Khanpur Hospital", null, upazila = "BD306758")
        val found = HospitalSearch.search(near, "BD3067", "", limit = 8, near = "BD306758").map { it.id }
        assertEquals(listOf("kh", "ct", "ng"), found)
        assertEquals("a better name match still leads", listOf("ng", "ct"),
            HospitalSearch.search(near, "BD3067", "নারা${yaOne}ণগঞ্জ", limit = 8, near = "BD306758").map { it.id })
    }
}
