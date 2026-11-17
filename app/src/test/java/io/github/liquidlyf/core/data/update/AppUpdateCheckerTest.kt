package io.github.liquidlyf.core.data.update

import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A32-4: the daily update.json check, against a local HTTP server and a temp folder. */
class AppUpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var now = Instant.parse("2026-11-17T06:00:00Z")
    private val day = Duration.ofHours(24)

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun store() = AppUpdateStore(File(tmp.root, "update"))

    private fun checker(enabled: Boolean = true) = AppUpdateChecker(
        OkHttpClient(),
        server.url("/liquidlyf/LiquidLyf/releases/latest/download/update.json").toString(),
        store(),
        Clock { now },
        enabled,
    )

    private fun updateJson(versionCode: Int = 12, rollout: Int = 100, minSupported: Int = 0) = """
        {"versionCode": $versionCode, "versionName": "1.0.$versionCode",
         "apkUrl": "https://github.com/liquidlyf/LiquidLyf/releases/download/v1.0.$versionCode/LiquidLyf.apk",
         "sha256": "${"a".repeat(64)}",
         "notesUrl": "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.$versionCode",
         "rolloutPercent": $rollout, "minSupportedVersionCode": $minSupported}
    """.trimIndent()

    private fun reply(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `the published app asks once a day and keeps the answer for Home`() = runTest {
        val checker = checker()
        reply(200, updateJson(versionCode = 12))
        val checked = checker.checkIfDue(day) as UpdateCheck.Checked
        assertEquals(12, checked.info!!.versionCode)
        assertTrue(checker.state(installedVersionCode = 11) is UpdateState.Available)
        assertEquals(UpdateState.None, checker.state(installedVersionCode = 12))

        now += Duration.ofHours(23)
        assertEquals(UpdateCheck.NotDue, checker.checkIfDue(day))
        assertEquals(1, server.requestCount)
        // The answer survives a restart.
        assertTrue(checker().state(installedVersionCode = 11) is UpdateState.Available)

        // The release was pulled: no stable release, no card.
        now += Duration.ofHours(1)
        reply(404)
        assertEquals(UpdateCheck.Checked(null), checker.checkIfDue(day))
        assertEquals(UpdateState.None, checker.state(installedVersionCode = 11))
    }

    @Test
    fun `below the minimum the update is required, whatever the rollout`() = runTest {
        val checker = checker()
        reply(200, updateJson(versionCode = 12, rollout = 0, minSupported = 12))
        checker.checkIfDue(day)
        assertTrue(checker.state(installedVersionCode = 11) is UpdateState.Required)
        assertEquals(UpdateState.None, checker.state(installedVersionCode = 12))
    }

    @Test
    fun `a broken file keeps the last good answer, and an error isn't a check`() = runTest {
        val checker = checker()
        reply(200, updateJson(versionCode = 12))
        checker.checkIfDue(day)
        now += day
        reply(200, "<html>Not quite JSON</html>")
        assertEquals(12, (checker.checkIfDue(day) as UpdateCheck.Checked).info!!.versionCode)

        now += day
        reply(503)
        assertEquals(UpdateCheck.Failed, checker.checkIfDue(day))
        reply(200, "x".repeat(AppUpdateChecker.MAX_BYTES.toInt() + 1)) // still due: tried again at once
        assertEquals(UpdateCheck.Failed, checker.checkIfDue(day))
        assertEquals(4, server.requestCount)
        assertTrue(checker.state(installedVersionCode = 11) is UpdateState.Available)
    }

    @Test
    fun `demo and dev builds never ask`() = runTest {
        val checker = checker(enabled = false)
        assertEquals(UpdateCheck.Off, checker.checkIfDue(day))
        assertEquals(UpdateState.None, checker.state(installedVersionCode = 1))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `each phone gets one random rollout ID and keeps it`() {
        val id = store().rolloutId()
        UUID.fromString(id)
        assertEquals(id, store().rolloutId())
        store().write(AppUpdateStore.State(checkedAtMillis = 1))
        assertEquals(id, store().rolloutId())
        assertNotEquals(id, AppUpdateStore(File(tmp.root, "another-phone")).rolloutId())
    }

    @Test
    fun `the app asks GitHub's newest stable release over HTTPS`() {
        val url = AppUpdates.URL.toHttpUrl()
        assertEquals("https", url.scheme)
        assertEquals("github.com", url.host)
        assertEquals("/liquidlyf/LiquidLyf/releases/latest/download/update.json", url.encodedPath)
    }
}
