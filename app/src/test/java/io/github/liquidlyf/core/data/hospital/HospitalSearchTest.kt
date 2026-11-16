package io.github.liquidlyf.core.data.hospital

import io.github.liquidlyf.core.model.PlaceName
import org.junit.Assert.assertEquals
import org.junit.Test

class HospitalSearchTest {

    // য় written as one character (U+09DF), and as য plus a nukta (U+09AF U+09BC).
    private val yaOne = "য়"
    private val yaTwo = "য়"

    private fun hospital(id: String, en: String, bn: String?, district: String = "BD3067") =
        Hospital(id, PlaceName(en, bn), address = null, district = district, upazila = null, location = null)

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
    fun `ignores case, stays in the district, and blank finds nothing`() {
        assertEquals(listOf("ct", "ng"), ids("HOSPITAL"))
        assertEquals(listOf("ct"), ids("city"))
        assertEquals(emptyList<String>(), ids("dhaka"))
        assertEquals(emptyList<String>(), ids("  "))
    }
}
