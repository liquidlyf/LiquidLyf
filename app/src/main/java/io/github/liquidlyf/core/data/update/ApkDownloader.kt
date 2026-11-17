package io.github.liquidlyf.core.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the "Get the update" flow shows while the new APK downloads. */
sealed interface ApkDownload {
    /** [total] comes from update.json or the server; null when neither says. */
    data class Progress(val bytes: Long, val total: Long?) : ApkDownload

    /** Downloaded, and its SHA-256 matches update.json: ready for Android's installer. */
    data class Ready(val apk: File) : ApkDownload

    data class Failed(val reason: Reason) : ApkDownload

    enum class Reason {
        /** Offline, or the connection dropped: try again. */
        Network,

        /** The file isn't the one the release lists (or is far too big): it was deleted. */
        WrongFile,
    }
}

/**
 * The in-app update download ("update a", 17 Nov 2026). Only ever starts from a tap, never by
 * itself (mobile data costs money). It uses the app's own HTTP client (the privacy policy's
 * "plain download, no IDs"), follows GitHub's redirect to its file host over HTTPS only, and
 * checks the SHA-256 from update.json while it writes. A wrong file is deleted on the spot.
 *
 * The APK goes to [dir] (the cache): [ApkInstaller] hands it to Android, and [cleanUp] removes
 * it at the next start, so nothing stays behind after the update.
 */
class ApkDownloader(http: OkHttpClient, private val dir: File) {
    private val client = http.newBuilder().cache(null).followSslRedirects(false).build()

    fun download(info: UpdateInfo): Flow<ApkDownload> = flow {
        dir.mkdirs()
        val part = File(dir, "LiquidLyf-${info.versionCode}.apk.part")
        val apk = File(dir, "LiquidLyf-${info.versionCode}.apk")
        val url = info.apkUrl.toHttpUrl()
        var ok = false
        try {
            val call = client.newCall(Request.Builder().url(url).build())
            val response = try {
                call.execute()
            } catch (_: IOException) {
                emit(ApkDownload.Failed(ApkDownload.Reason.Network))
                return@flow
            }
            response.use {
                // No https → http redirect is followed (above), so this stays https.
                if (!response.isSuccessful || response.request.url.scheme != url.scheme) {
                    emit(ApkDownload.Failed(ApkDownload.Reason.Network))
                    return@flow
                }
                val total = info.apkSizeBytes ?: response.body.contentLength().takeIf { it > 0 }
                val limit = minOf(AppUpdates.MAX_APK_BYTES, (info.apkSizeBytes ?: AppUpdates.MAX_APK_BYTES))
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var shown = -1L
                emit(ApkDownload.Progress(0, total))
                try {
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive() // "Cancel" stops it
                                val n = input.read(buffer)
                                if (n < 0) break
                                bytes += n
                                if (bytes > limit) {
                                    emit(ApkDownload.Failed(ApkDownload.Reason.WrongFile))
                                    return@flow
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                // About every 1%, not for every 8 KB.
                                if (shown < 0 || bytes - shown >= PROGRESS_STEP) {
                                    shown = bytes
                                    emit(ApkDownload.Progress(bytes, total))
                                }
                            }
                            output.fd.sync()
                        }
                    }
                } catch (_: IOException) {
                    emit(ApkDownload.Failed(ApkDownload.Reason.Network))
                    return@flow
                }
                emit(ApkDownload.Progress(bytes, total))
                if (hex(digest.digest()) != info.sha256 || (info.apkSizeBytes != null && bytes != info.apkSizeBytes)) {
                    emit(ApkDownload.Failed(ApkDownload.Reason.WrongFile))
                    return@flow
                }
                apk.delete()
                if (!part.renameTo(apk)) {
                    emit(ApkDownload.Failed(ApkDownload.Reason.Network))
                    return@flow
                }
                ok = true
                emit(ApkDownload.Ready(apk))
            }
        } finally {
            // Whatever stopped it (an error, a wrong file, Cancel), no half or wrong file stays.
            part.delete()
            if (!ok) apk.delete()
        }
    }.flowOn(Dispatchers.IO)

    /** At every start: the APK from the last update (installed or not) is no longer needed. */
    fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER = 64 * 1024
        const val PROGRESS_STEP = 64L * 1024
    }
}
