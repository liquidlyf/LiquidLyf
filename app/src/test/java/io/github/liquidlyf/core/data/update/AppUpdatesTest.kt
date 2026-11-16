package io.github.liquidlyf.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {

    /** Written by app/scripts/make_update_json.py for a real release APK (17 Nov 2026). */
    private val fromTheScript = """
        {
          "versionCode": 1,
          "versionName": "1.0.0-alpha01",
          "apkUrl": "https://github.com/liquidlyf/LiquidLyf/releases/download/v1.0.0-alpha01/LiquidLyf.apk",
          "sha256": "b22fdc3e99c911642754bd86e8802648be2afc87be0768d4b0c84960aa386e22",
          "notesUrl": "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.0-alpha01",
          "rolloutPercent": 25,
          "minSupportedVersionCode": 0
        }
    """.trimIndent()

    private val v12 = UpdateInfo(
        versionCode = 12, versionName = "1.0.0",
        apkUrl = "https://github.com/liquidlyf/LiquidLyf/releases/download/v1.0.0/LiquidLyf.apk",
        sha256 = "0".repeat(64), notesUrl = "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.0",
    )

    @Test
    fun `reads what the release script writes`() {
        val info = AppUpdates.parse(fromTheScript)
        assertNotNull(info)
        info!!
        assertEquals(1, info.versionCode)
        assertEquals("1.0.0-alpha01", info.versionName)
        assertEquals(25, info.rolloutPercent)
    }

    @Test
    fun `a newer field or two from a later app version doesn't break an older app`() {
        val withExtra = fromTheScript.replace("\"rolloutPercent\"", "\"channel\": \"stable\", \"rolloutPercent\"")
        assertNotNull(AppUpdates.parse(withExtra))
    }

    @Test
    fun `links anywhere but LiquidLyf's own releases, or a bad checksum, are refused`() {
        val good = fromTheScript
        listOf(
            good.replace("https://github.com/liquidlyf/LiquidLyf/releases/download", "https://example.com/download"),
            good.replace("https://github.com/liquidlyf/LiquidLyf/releases/download", "http://github.com/liquidlyf/LiquidLyf/releases/download"),
            good.replace("https://github.com/liquidlyf/LiquidLyf/releases/tag", "https://github.com/someone/LiquidLyf/releases/tag"),
            good.replace("LiquidLyf.apk", "LiquidLyf.zip"),
            good.replace("b22fdc3e", "B22FDC3E"),
            good.replace("b22fdc3e", "b22f"),
            good.replace("\"rolloutPercent\": 25", "\"rolloutPercent\": 101"),
            good.replace("\"versionCode\": 1", "\"versionCode\": 0"),
            "not json",
            "{}",
        ).forEach { assertNull(it, AppUpdates.parse(it)) }
    }

    @Test
    fun `a newer version is offered to the phones inside the rollout only`() {
        val id = "phone-a"
        val bucket = AppUpdates.rolloutBucket(id)
        assertEquals(UpdateState.Available(v12.copy(rolloutPercent = bucket + 1)),
            AppUpdates.decide(installed = 11, info = v12.copy(rolloutPercent = bucket + 1), installationId = id))
        assertEquals(UpdateState.None, AppUpdates.decide(11, v12.copy(rolloutPercent = bucket), id))
        assertEquals(UpdateState.None, AppUpdates.decide(11, v12.copy(rolloutPercent = 0), id))
        assertTrue(AppUpdates.decide(11, v12.copy(rolloutPercent = 100), id) is UpdateState.Available)
    }

    @Test
    fun `the same or an older version is never offered`() {
        assertEquals(UpdateState.None, AppUpdates.decide(12, v12, "phone-a"))
        assertEquals(UpdateState.None, AppUpdates.decide(13, v12.copy(minSupportedVersionCode = 12), "phone-a"))
        assertEquals(UpdateState.None, AppUpdates.decide(12, null, "phone-a"))
    }

    @Test
    fun `below the minimum, or the emergency switch, the update is required whatever the rollout`() {
        val info = v12.copy(rolloutPercent = 0, minSupportedVersionCode = 10)
        assertEquals(UpdateState.Required(info), AppUpdates.decide(9, info, "phone-a"))
        assertEquals(UpdateState.None, AppUpdates.decide(10, info, "phone-a"))
        assertEquals(UpdateState.Required(info), AppUpdates.decide(10, info, "phone-a", forceBelowVersionCode = 11))
    }

    @Test
    fun `a phone keeps its rollout place, and phones spread evenly over 0-99`() {
        assertEquals(AppUpdates.rolloutBucket("phone-a"), AppUpdates.rolloutBucket("phone-a"))
        val counts = IntArray(100)
        repeat(20_000) { counts[AppUpdates.rolloutBucket("installation-$it")]++ }
        assertTrue(counts.all { it in 1..400 })
        // A 25% rollout reaches about a quarter of phones.
        val reached = (0 until 20_000).count { AppUpdates.rolloutBucket("installation-$it") < 25 }
        assertTrue("$reached of 20000", reached in 4_600..5_400)
    }
}
