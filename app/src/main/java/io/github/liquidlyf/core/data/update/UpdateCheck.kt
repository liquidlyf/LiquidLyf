package io.github.liquidlyf.core.data.update

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Singleton

sealed interface UpdateCheck {
    data object NotDue : UpdateCheck

    /** GitHub answered: [info] is the newest stable release's update.json, null while there's none. */
    data class Checked(val info: UpdateInfo?) : UpdateCheck

    /** Offline or an error: try again on the next open or the next daily run. */
    data object Failed : UpdateCheck

    /** Builds that aren't the published app (demo, dev) never ask: their app ID differs from the APK's. */
    data object Off : UpdateCheck
}

/**
 * Phase 9.4 (A32-4, 17 Nov 2026): is there a newer LiquidLyf? Once a day, in [DailyCheckWorker],
 * the published app downloads [AppUpdates.URL] (about 400 bytes; GitHub sees the phone's IP
 * address, like any website, as the privacy policy says). The answer is kept for the Home
 * "Update available" card, which comes with the UI work: [state].
 */
class AppUpdateChecker(
    http: OkHttpClient,
    url: String,
    private val store: AppUpdateStore,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    private val url = url.toHttpUrl()

    // releases/latest/download/… redirects twice (to the release, then to GitHub's file host),
    // so any https host may answer: the file's own links are what AppUpdates.parse checks.
    private val client = http.newBuilder().cache(null).followSslRedirects(false).build()
    private val mutex = Mutex()

    suspend fun checkIfDue(after: Duration): UpdateCheck = mutex.withLock {
        if (!enabled) return@withLock UpdateCheck.Off
        withContext(Dispatchers.IO) {
            val state = store.read()
            val now = clock.now()
            val last = state.checkedAtMillis?.let(Instant::ofEpochMilli)
            if (last != null && !now.isBefore(last) && Duration.between(last, now) < after) return@withContext UpdateCheck.NotDue

            val info = when (val reply = fetch()) {
                Reply.Failed -> return@withContext UpdateCheck.Failed
                Reply.NoRelease -> null
                // A broken file: keep what we knew. A fixed release replaces it tomorrow.
                is Reply.Body -> AppUpdates.parse(reply.text) ?: state.info
            }
            store.write(state.copy(info = info, checkedAtMillis = now.toEpochMilli()))
            UpdateCheck.Checked(info)
        }
    }

    /** What Home shows for this build. [forceBelowVersionCode]: Remote Config's emergency switch. */
    suspend fun state(installedVersionCode: Int, forceBelowVersionCode: Int = 0): UpdateState = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext UpdateState.None
        val state = store.read()
        AppUpdates.decide(installedVersionCode, state.info, store.rolloutId(), forceBelowVersionCode)
    }

    private sealed interface Reply {
        data object NoRelease : Reply

        data class Body(val text: String) : Reply

        data object Failed : Reply
    }

    private suspend fun fetch(): Reply = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // No https → http redirect is followed (above), so this stays https in the app.
                if (response.request.url.scheme != url.scheme) return@use Reply.Failed
                when (response.code) {
                    200 -> {
                        val source = response.body.source()
                        if (source.request(MAX_BYTES + 1)) Reply.Failed else Reply.Body(source.buffer.readUtf8())
                    }
                    // No stable release yet, or it has no update.json.
                    404 -> Reply.NoRelease
                    else -> Reply.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Reply.Failed
        }
    }

    companion object {
        const val MAX_BYTES = 64L * 1024
    }
}

/**
 * The last update.json, when GitHub last answered, and this phone's random rollout ID. All in
 * the app's files, which are never backed up (data_extraction_rules.xml), so the ID stays on
 * this phone and is never sent anywhere: the rollout bucket is worked out here.
 */
class AppUpdateStore(private val dir: File) {
    @Serializable
    data class State(
        val info: UpdateInfo? = null,
        val checkedAtMillis: Long? = null,
        val rolloutId: String? = null,
    )

    private val file = File(dir, "update-check.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): State = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())

    /** The rollout ID is made on the first write and kept after that. */
    @Synchronized
    fun write(state: State) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(State.serializer(), state.copy(rolloutId = state.rolloutId ?: read().rolloutId ?: UUID.randomUUID().toString())))
        // Android renames over the old file in one step; Windows (unit tests) needs it gone first.
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("can't save ${file.name}")
        }
    }

    @Synchronized
    fun rolloutId(): String = read().rolloutId ?: run {
        runCatching { write(read()) }
        read().rolloutId ?: UUID.randomUUID().toString() // storage broken: a different bucket each time
    }
}

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    fun checker(@ApplicationContext context: Context, http: OkHttpClient, clock: Clock): AppUpdateChecker = AppUpdateChecker(
        http = http,
        url = AppUpdates.URL,
        store = AppUpdateStore(File(context.filesDir, "update")),
        clock = clock,
        enabled = BuildConfig.FLAVOR == "prod",
    )
}
