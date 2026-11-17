package io.github.liquidlyf.core.data.hospital

import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

/** The silent refresh from the website (plan D14), against a local HTTP server and a temp folder. */
class HospitalListsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val bdGeo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geo = object : GeoRepository {
        override suspend fun geo() = bdGeo
    }
    private var now = Instant.parse("2026-11-17T06:00:00Z")
    private var builtIn: String? = null
    private val day = Duration.ofHours(24)

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    /** A new one is a new process: it reads what the last one saved. */
    private fun lists() = HospitalLists(
        builtIn = { builtIn },
        store = HospitalListStore(File(tmp.root, "hospitals")),
        download = HospitalListDownload(OkHttpClient(), server.url("/data/hospitals.json").toString()),
        geo = geo,
        clock = Clock { now },
    )

    private fun file(version: Int, size: Int = 3) = hospitalsFile(version, *(1..size).map { hospitalRow("h$it") }.toTypedArray())

    private fun ok(body: String, etag: String? = null) = server.enqueue(
        MockResponse.Builder().code(200).body(body).apply { if (etag != null) addHeader("ETag", etag) }.build(),
    )

    private fun notModified() = server.enqueue(MockResponse.Builder().code(304).build())

    private fun sentEtag(): String? = server.takeRequest().headers["If-None-Match"]

    @Test
    fun `a fresh install gets the website's list on its first open, then asks at most once a day`() = runTest {
        val lists = lists()
        assertEquals(emptyList<Hospital>(), lists.search("BD3026", "hospital"))
        ok(file(1), etag = "\"v1\"")
        assertEquals(HospitalRefresh.Updated(1), lists.refreshIfDue(day))
        assertNull(sentEtag())
        assertEquals(listOf("h1", "h2", "h3"), lists.search("BD3026", "hospital").map { it.id })

        now += Duration.ofHours(2)
        assertEquals(HospitalRefresh.NotDue, lists.refreshIfDue(day))
        assertEquals(1, server.requestCount)

        // A day later the site says "no change" (304, no body), and the saved list stays.
        now += Duration.ofHours(23)
        notModified()
        assertEquals(HospitalRefresh.Unchanged, lists.refreshIfDue(day))
        assertEquals("\"v1\"", sentEtag())
        assertEquals(1, lists().list().version)
    }

    @Test
    fun `the higher version wins, so an app update with a newer built-in list beats an older download`() = runTest {
        builtIn = file(1)
        ok(file(2), etag = "\"v2\"")
        assertEquals(HospitalRefresh.Updated(2), lists().refreshIfDue(day))
        assertEquals(2, lists().list().version)

        builtIn = file(3) // the app was updated
        val updated = lists()
        assertEquals(3, updated.list().version)
        now += day
        ok(file(2), etag = "\"v2-again\"") // the site still has 2
        assertEquals(HospitalRefresh.Unchanged, updated.refreshIfDue(day))
        assertEquals(3, updated.list().version)
        assertNull(sentEtag())
        assertEquals("\"v2\"", sentEtag())
        // Nothing newer was there: its ETag is kept, so tomorrow's answer is a 304.
        now += day
        notModified()
        assertEquals(HospitalRefresh.Unchanged, updated.refreshIfDue(day))
        assertEquals("\"v2-again\"", sentEtag())
    }

    @Test
    fun `a refused file changes nothing, and its ETag isn't kept so it's looked at again`() = runTest {
        builtIn = file(1, size = 10)
        val lists = lists()
        ok(file(2, size = 10), etag = "\"good\"")
        assertEquals(HospitalRefresh.Updated(2), lists.refreshIfDue(day))
        server.takeRequest()

        now += day
        ok(file(3, size = 7), etag = "\"short\"") // 70% of the list
        assertTrue(lists.refreshIfDue(day) is HospitalRefresh.Rejected)
        assertEquals("\"good\"", sentEtag())
        assertEquals(2, lists.list().version)
        assertEquals(2, lists().list().version)

        now += Duration.ofHours(2) // a refused answer still counts as today's check
        assertEquals(HospitalRefresh.NotDue, lists.refreshIfDue(day))

        now += day
        ok(hospitalsFile(4, *(1..10).map { hospitalRow("x$it", district = "BD9999", upazila = null) }.toTypedArray()))
        assertTrue(lists.refreshIfDue(day) is HospitalRefresh.Rejected)
        assertEquals("\"good\"", sentEtag()) // not "short"
        now += day
        ok("<html>Site not found</html>")
        assertTrue(lists.refreshIfDue(day) is HospitalRefresh.Rejected)
        assertEquals(2, lists().list().version)
    }

    @Test
    fun `offline or a server error isn't a check, so the next open tries again`() = runTest {
        val lists = lists()
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(HospitalRefresh.Failed, lists.refreshIfDue(day))
        ok(file(1))
        assertEquals(HospitalRefresh.Updated(1), lists.refreshIfDue(day))
        assertEquals(2, server.requestCount)

        server.close() // nothing listening: an IOException
        now += day
        assertEquals(HospitalRefresh.Failed, lists.refreshIfDue(day))
        assertEquals(1, lists.list().version)
    }

    @Test
    fun `only our own site counts, and a file over 2 MB is refused`() = runTest {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(MockResponse.Builder().code(200).body(file(1)).build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/data/hospitals.json")).build())
            assertEquals(HospitalRefresh.Failed, lists().refreshIfDue(day))
        }
        ok(file(1).replace("\"hospitals\":[", "\"padding\":\"${"x".repeat(HospitalListDownload.MAX_BYTES.toInt())}\",\"hospitals\":["))
        assertEquals(HospitalRefresh.Failed, lists().refreshIfDue(day))
        assertEquals(0, lists().list().version)
    }

    @Test
    fun `a damaged saved file falls back to the built-in list, and the next check fetches it in full`() = runTest {
        builtIn = file(1)
        ok(file(2), etag = "\"v2\"")
        assertEquals(HospitalRefresh.Updated(2), lists().refreshIfDue(day))
        server.takeRequest()
        File(tmp.root, "hospitals/hospitals.json").writeText("{\"version\":2,\"hosp")

        val restarted = lists()
        assertEquals(1, restarted.list().version)
        ok(file(2), etag = "\"v2\"")
        assertEquals(HospitalRefresh.Updated(2), restarted.refreshIfDue(day)) // due again, without the ETag
        assertNull(sentEtag())
    }

    @Test
    fun `a clock set back counts as due`() = runTest {
        ok(file(1))
        lists().refreshIfDue(day)
        now -= Duration.ofDays(3)
        notModified()
        assertEquals(HospitalRefresh.Unchanged, lists().refreshIfDue(day))
    }

    @Test
    fun `the phones ask our website over HTTPS`() {
        val url = HospitalListDownload.URL.toHttpUrl()
        assertEquals("https", url.scheme)
        assertEquals("liquidlyf.github.io", url.host)
        assertEquals("/data/hospitals.json", url.encodedPath)
    }
}
