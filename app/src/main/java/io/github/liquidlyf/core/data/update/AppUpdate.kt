package io.github.liquidlyf.core.data.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Phase 9.4: `update.json`, attached to every GitHub release. LiquidLyf ships as an APK on GitHub
 * Releases (no Play, so no in-app update API): once a day the app downloads [AppUpdates.URL], which
 * always points at the newest **stable** release. It's a plain file download, not a GitHub API
 * call, so many phones behind one mobile-network IP can't use up the API's hourly limit. The
 * release workflow writes the file with `app/scripts/make_update_json.py`.
 *
 * ```json
 * { "versionCode": 12, "versionName": "1.0.0",
 *   "apkUrl": "https://github.com/liquidlyf/LiquidLyf/releases/download/v1.0.0/LiquidLyf.apk",
 *   "sha256": "<64 hex digits of LiquidLyf.apk>",
 *   "notesUrl": "https://github.com/liquidlyf/LiquidLyf/releases/tag/v1.0.0",
 *   "rolloutPercent": 100, "minSupportedVersionCode": 1 }
 * ```
 */
@Serializable
data class UpdateInfo(
    /** The release's `versionCode`; it only ever goes up (Android won't install a lower one). */
    val versionCode: Int,
    val versionName: String,
    /** That release's own APK (not `latest`), so [sha256] always matches it. */
    val apkUrl: String,
    val sha256: String,
    /** The release page, with the notes in English and Bangla. */
    val notesUrl: String,
    /** A staged rollout: 0–100% of phones are told about it (see [AppUpdates.rolloutBucket]). */
    val rolloutPercent: Int = 100,
    /** Older installed versions must update: they get a blocking "Please update" screen. */
    val minSupportedVersionCode: Int = 0,
)

/** What the app should show for an [UpdateInfo]. */
sealed interface UpdateState {
    data object None : UpdateState

    /** A dismissible "Update available" card on Home. */
    data class Available(val info: UpdateInfo) : UpdateState

    /** The blocking "Please update" screen, with the download link. */
    data class Required(val info: UpdateInfo) : UpdateState
}

object AppUpdates {
    const val URL = "https://github.com/liquidlyf/LiquidLyf/releases/latest/download/update.json"

    /** Only links to LiquidLyf's own releases are shown: the app opens them in the browser. */
    private const val RELEASES = "https://github.com/liquidlyf/LiquidLyf/releases/"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val JSON = Json { ignoreUnknownKeys = true }

    /** The downloaded file, or null if it isn't a well-formed update.json for LiquidLyf. */
    fun parse(text: String): UpdateInfo? =
        runCatching { JSON.decodeFromString(UpdateInfo.serializer(), text) }.getOrNull()?.takeIf { it.isValid() }

    private fun UpdateInfo.isValid(): Boolean =
        versionCode > 0 && versionName.isNotBlank() && rolloutPercent in 0..100 && minSupportedVersionCode >= 0 &&
            SHA256.matches(sha256) && apkUrl.startsWith(RELEASES) && apkUrl.endsWith(".apk") && notesUrl.startsWith(RELEASES)

    /**
     * [installed] is this build's `versionCode`. [forceBelowVersionCode] is the emergency switch
     * (Remote Config `force_update_below_version_code`), 0 when unset. An update that's required
     * ignores the rollout: an old version may be unsafe, so every phone below it hears about it.
     */
    fun decide(installed: Int, info: UpdateInfo?, installationId: String, forceBelowVersionCode: Int = 0): UpdateState {
        if (info == null || info.versionCode <= installed) return UpdateState.None
        return when {
            installed < maxOf(info.minSupportedVersionCode, forceBelowVersionCode) -> UpdateState.Required(info)
            rolloutBucket(installationId) < info.rolloutPercent -> UpdateState.Available(info)
            else -> UpdateState.None
        }
    }

    /** This phone's place in a staged rollout, 0–99: the same every day, spread evenly across phones. */
    fun rolloutBucket(installationId: String): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray())
        val first = hash.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return (first % 100).toInt()
    }
}
