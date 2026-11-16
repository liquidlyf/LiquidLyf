package io.github.liquidlyf.core.data.geo

import io.github.liquidlyf.core.domain.Tiers
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.GeoAreaType
import io.github.liquidlyf.core.model.PlaceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the real bundled file, assets/bd-geo.json. */
class BdGeoTest {

    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val districts = geo.divisions.flatMap { it.districts }
    private val areas = districts.flatMap { it.areas }

    @Test
    fun `has every division, district, upazila and city corporation`() {
        assertEquals(8, geo.divisions.size)
        assertEquals(64, districts.size)
        assertEquals(495, areas.count { it.type == GeoAreaType.UPAZILA })
        assertEquals(12, areas.count { it.type == GeoAreaType.CITY_CORPORATION })
    }

    @Test
    fun `every code is unique and safe for Firestore and FCM topic names`() {
        val codes = geo.divisions.map { it.code } + districts.map { it.code } + areas.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { assertTrue(it, Validators.isCode(it)) }
        // Every listed area can become a donor's topics without an exception.
        for (d in districts) for (a in d.areas) {
            assertEquals(3, Tiers.donorTopics(BloodGroup.O_NEG, d.divisionCode, d.code, a.code).size)
        }
    }

    @Test
    fun `neighbours are real, never the district itself, and go both ways`() {
        for (d in districts) {
            assertTrue("${d.name.en} has no neighbours", d.neighbours.isNotEmpty())
            for (n in d.neighbours) {
                assertFalse(n == d.code)
                val other = assertNotNullAndGet(geo.district(n), n)
                assertTrue("${other.name.en} -> ${d.name.en}", d.code in other.neighbours)
            }
        }
    }

    @Test
    fun `every place has a Bangla name`() {
        val names = geo.divisions.map { it.name } + districts.map { it.name } + areas.map { it.name }
        names.forEach { assertTrue(it.en, !it.bn.isNullOrBlank()) }
    }

    @Test
    fun `lookups and area validation`() {
        val dhaka = assertNotNullAndGet(geo.district("BD3026"), "BD3026")
        assertEquals("Dhaka", dhaka.name.en)
        assertEquals("BD30", dhaka.divisionCode)
        assertTrue("BD3033" in geo.neighbours("BD3026")) // Gazipur
        val dscc = assertNotNullAndGet(geo.area("BD30262000"), "BD30262000")
        assertEquals(GeoAreaType.CITY_CORPORATION, dscc.type)
        assertEquals("ঢাকা দক্ষিণ সিটি কর্পোরেশন", dscc.name.localized(AppLocale.BN))

        assertTrue(geo.isValid(Area("BD30", "BD3026", "BD30262000")))
        assertFalse(geo.isValid(Area("BD20", "BD3026", "BD30262000"))) // wrong division
        assertFalse(geo.isValid(Area("BD30", "BD3033", "BD30262000"))) // area in another district
        assertFalse(geo.isValid(Area("BD30", "BD3026", "302616"))) // an old BBS code
        assertEquals(emptyList<String>(), geo.neighbours("nowhere"))
    }

    @Test
    fun `names the maintainer corrected stay corrected (16 Nov 2026)`() {
        val zianagar = assertNotNullAndGet(geo.area("BD10790090"), "BD10790090") // was Indurkani / ইন্দুরকানী
        assertEquals("Zianagar", zianagar.name.en)
        assertEquals("জিয়ানগর", zianagar.name.bn)
        assertEquals("Bhuapur", geo.area("BD30930019")?.name?.en) // BBS spells it "Bhuanpur"
        assertEquals("চর রাজিবপুর", geo.area("BD55490008")?.name?.bn)
        assertFalse(areas.any { it.name.bnNeedsReview })
    }

    @Test
    fun `names fall back to English when there is no Bangla yet`() {
        assertEquals("Lama", PlaceName("Lama", null).localized(AppLocale.BN))
        assertEquals("Lama", PlaceName("Lama", "লামা").localized(AppLocale.EN))
        assertEquals("লামা", PlaceName("Lama", "লামা").localized(AppLocale.BN))
    }

    private fun <T : Any> assertNotNullAndGet(value: T?, what: String): T {
        assertNotNull(what, value)
        return value!!
    }
}
