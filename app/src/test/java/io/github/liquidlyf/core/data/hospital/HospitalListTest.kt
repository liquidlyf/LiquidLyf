package io.github.liquidlyf.core.data.hospital

import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.hospital.HospitalListRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** hospitals.json as B's generator writes it (firebase/seed/hospitals.mjs), and the phone's checks on it. */
class HospitalListTest {
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())

    @Test
    fun `reads B's file, ignoring the fields the app doesn't use`() {
        val list = HospitalSearch.parse(
            hospitalsFile(
                3,
                hospitalRow("dhk-dmch", hasBloodBank = true),
                // Optional fields are left out, never null or "".
                """{"id":"dhk-plain","name":"Plain Hospital","type":"PRIVATE","district":"BD3026"}""",
            ),
        )
        assertEquals(3, list.version)
        val (dmch, plain) = list.hospitals
        assertEquals("Hospital dhk-dmch", dmch.name.en)
        assertEquals("হাসপাতাল dhk-dmch", dmch.name.bn)
        assertEquals("BD30262000", dmch.upazila)
        assertEquals(23.7256, dmch.location!!.lat, 0.0)
        assertEquals("wh0qcp1e8", dmch.location!!.geohash)
        assertNull(plain.name.bn)
        assertNull(plain.upazila)
        assertNull(plain.address)
        assertNull(plain.location)
    }

    @Test
    fun `refuses a file that isn't well formed`() {
        val good = hospitalRow("a")
        listOf(
            "version 0" to hospitalsFile(0, good),
            "the 04:12 draft's bare list" to "[$good]",
            "a repeated id" to hospitalsFile(1, good, good),
            "a blank id" to hospitalsFile(1, hospitalRow(" ")),
            "a blank name" to hospitalsFile(1, good.replace("\"Hospital a\"", "\" \"")),
            "a name over 120" to hospitalsFile(1, good.replace("\"Hospital a\"", "\"${"x".repeat(121)}\"")),
            "a blank Bangla name" to hospitalsFile(1, good.replace("\"হাসপাতাল a\"", "\"\"")),
            "part of a map point" to hospitalsFile(1, good.replace(",\"geohash\":\"wh0qcp1e8\"", "")),
            "a point outside Bangladesh" to hospitalsFile(1, good.replace("23.7256", "51.5")),
            "not JSON" to "<html>404</html>",
        ).forEach { (why, text) -> assertNull(why, HospitalSearch.parseOrNull(text)) }
        // The limits themselves are fine.
        assertNotNull(HospitalSearch.parseOrNull(hospitalsFile(1, good.replace("\"Hospital a\"", "\"${"x".repeat(120)}\""))))
    }

    @Test
    fun `only a newer list with real area codes and most of the hospitals replaces the current one`() {
        val current = list(4, size = 10)
        assertEquals(Verdict.Accept, HospitalListRules.check(list(5, size = 10), current, geo))
        assertEquals(Verdict.Accept, HospitalListRules.check(list(5, size = 8), current, geo)) // 80%
        assertTrue(HospitalListRules.check(list(5, size = 7), current, geo) is Verdict.Reject)
        assertEquals(Verdict.NotNewer, HospitalListRules.check(list(4, size = 12), current, geo))
        assertEquals(Verdict.NotNewer, HospitalListRules.check(list(3, size = 12), current, geo))
        assertTrue(HospitalListRules.check(null, current, geo) is Verdict.Reject)
        // With no list yet, any sound one will do; an empty one never.
        assertEquals(Verdict.Accept, HospitalListRules.check(list(1, size = 1), HospitalList.EMPTY, geo))
        assertTrue(HospitalListRules.check(list(1, size = 0), HospitalList.EMPTY, geo) is Verdict.Reject)

        fun one(district: String, upazila: String?) =
            HospitalSearch.parse(hospitalsFile(5, hospitalRow("x", district = district, upazila = upazila)))
        assertEquals(Verdict.Accept, HospitalListRules.check(one("BD4041", "BD40410004"), HospitalList.EMPTY, geo)) // Jashore
        assertEquals(Verdict.Accept, HospitalListRules.check(one("BD4041", null), HospitalList.EMPTY, geo))
        assertTrue(HospitalListRules.check(one("BD9999", null), HospitalList.EMPTY, geo) is Verdict.Reject)
        // A Narayanganj upazila under Dhaka district.
        assertTrue(HospitalListRules.check(one("BD3026", "BD30670002"), HospitalList.EMPTY, geo) is Verdict.Reject)
    }

    @Test
    fun `the built-in list, once there is one, passes the checks a downloaded one must pass`() {
        val asset = File("src/main/assets/${HospitalLists.ASSET}")
        assumeTrue("no built-in hospital list yet (B26: B's checked Dhaka rows)", asset.isFile)
        val list = HospitalSearch.parse(asset.readText())
        assertEquals(Verdict.Accept, HospitalListRules.check(list, HospitalList.EMPTY, geo))
        assertTrue("${asset.length()} bytes", asset.length() <= HospitalListDownload.MAX_BYTES)
    }

    private fun list(version: Int, size: Int) =
        HospitalSearch.parse(hospitalsFile(version, *(1..size).map { hospitalRow("h$it") }.toTypedArray()))
}

/** One row in B's field order: id, name, nameBn?, type, district, upazila?, address?, lat?, lng?, geohash?, hasBloodBank?. */
fun hospitalRow(
    id: String,
    district: String = "BD3026",
    upazila: String? = "BD30262000",
    hasBloodBank: Boolean = false,
): String = buildString {
    append("""{"id":"$id","name":"Hospital $id","nameBn":"হাসপাতাল $id","type":"GOVERNMENT","district":"$district"""")
    if (upazila != null) append(""","upazila":"$upazila"""")
    append(""","address":"Bakshibazar, Dhaka","lat":23.7256,"lng":90.39757,"geohash":"wh0qcp1e8"""")
    if (hasBloodBank) append(""","hasBloodBank":true""")
    append("}")
}

fun hospitalsFile(version: Int, vararg rows: String): String =
    """{"version":$version,"licence":"ODbL-1.0","attribution":"Hospital locations © OpenStreetMap contributors","hospitals":[${rows.joinToString(",")}]}"""
