package io.github.liquidlyf.core.data.update

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** "update a": the in-app download, against a local HTTP server and a temp folder. */
class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val apk = Random(7).nextBytes(300 * 1024)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File
    private lateinit var downloader: ApkDownloader

    @Before
    fun setUp() {
        server.start()
        dir = File(tmp.root, "update-apk")
        downloader = ApkDownloader(OkHttpClient(), dir)
    }

    @After fun tearDown() = server.close()

    private fun info(sha: String = sha256, size: Long? = apk.size.toLong()) = UpdateInfo(
        versionCode = 2, versionName = "1.0.0-alpha02",
        apkUrl = server.url("/liquidlyf/LiquidLyf/releases/download/v1.0.0-alpha02/LiquidLyf.apk").toString(),
        sha256 = sha, notesUrl = "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.0-alpha02",
        apkSizeBytes = size,
    )

    private fun serve(body: ByteArray = apk, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(Buffer().write(body)).build())

    private fun leftovers() = dir.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun `downloads with progress, checks the fingerprint and leaves exactly the APK`() = runTest {
        serve()
        val steps = downloader.download(info()).toList()
        val progress = steps.filterIsInstance<ApkDownload.Progress>()
        assertEquals(ApkDownload.Progress(0, apk.size.toLong()), progress.first())
        assertEquals(apk.size.toLong(), progress.last().bytes)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.bytes >= a.bytes })
        val ready = steps.last() as ApkDownload.Ready
        assertArrayEquals(apk, ready.apk.readBytes())
        assertEquals(listOf("LiquidLyf-2.apk"), leftovers())
    }

    @Test
    fun `without a size in update_json the server's length is shown`() = runTest {
        serve()
        val first = downloader.download(info(size = null)).first()
        assertEquals(ApkDownload.Progress(0, apk.size.toLong()), first)
    }

    @Test
    fun `a file that isn't the release's is deleted`() = runTest {
        serve()
        assertEquals(ApkDownload.Failed(ApkDownload.Reason.WrongFile), downloader.download(info(sha = "0".repeat(64))).toList().last())
        assertEquals(emptyList<String>(), leftovers())
        // Longer than update.json says: stopped as soon as it passes the size.
        serve(apk + ByteArray(10))
        assertEquals(ApkDownload.Failed(ApkDownload.Reason.WrongFile), downloader.download(info()).toList().last())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `a server error or no connection says try again, and leaves nothing`() = runTest {
        serve(code = 503)
        assertEquals(ApkDownload.Failed(ApkDownload.Reason.Network), downloader.download(info()).toList().last())
        server.close()
        assertEquals(ApkDownload.Failed(ApkDownload.Reason.Network), downloader.download(info()).toList().last())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `Cancel stops the download and leaves nothing`() = runTest {
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(apk)).throttleBody(64 * 1024, 200, TimeUnit.MILLISECONDS).build(),
        )
        // The first progress after some bytes, then the person cancels (the collector stops).
        val partway = downloader.download(info()).first { it is ApkDownload.Progress && it.bytes > 0 }
        assertNotNull(partway)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `the next start removes the last update's APK`() = runTest {
        serve()
        downloader.download(info()).toList()
        File(dir, "LiquidLyf-1.apk.part").writeText("x")
        downloader.cleanUp()
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `update_json may carry the APK's size, as the release script now writes it`() {
        // make_update_json.py's output for the local release APK (17 Nov 2026).
        val text = """
            {"versionCode": 2, "versionName": "1.0.0-alpha02",
             "apkUrl": "https://github.com/liquidlyf/LiquidLyf/releases/download/v1.0.0-alpha02/LiquidLyf.apk",
             "sha256": "567a17c26ec789c94bae502b593adf588e0b9d8f14b3666ee97b60a0c73da488",
             "notesUrl": "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.0-alpha02",
             "rolloutPercent": 100, "minSupportedVersionCode": 0, "apkSizeBytes": 4456319}
        """.trimIndent()
        assertEquals(4_456_319L, AppUpdates.parse(text)!!.apkSizeBytes)
        assertNull(AppUpdates.parse(text.replace("4456319", "0")))
        assertNull(AppUpdates.parse(text.replace("4456319", "${AppUpdates.MAX_APK_BYTES + 1}")))
        assertNull(AppUpdates.parse(text.replace(", \"apkSizeBytes\": 4456319", ""))!!.apkSizeBytes)
    }
}
