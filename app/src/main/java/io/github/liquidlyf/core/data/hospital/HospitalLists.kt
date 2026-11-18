package io.github.liquidlyf.core.data.hospital

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Singleton

/** The website's copy is checked in the background (plan D14); nobody is ever asked or told. */
interface HospitalListRefresher {
    /** Asks the website for a newer list, unless the last answer came less than [after] ago. */
    suspend fun refreshIfDue(after: Duration): HospitalRefresh
}

sealed interface HospitalRefresh {
    /** Checked less than a day ago: nothing was sent. */
    data object NotDue : HospitalRefresh

    /** The website has nothing newer than this phone's list. */
    data object Unchanged : HospitalRefresh

    data class Updated(val version: Int) : HospitalRefresh

    /** The website's file was refused ([HospitalListRules]); the phone keeps its list. */
    data class Rejected(val reason: String) : HospitalRefresh

    /** Offline, an error, or not our site: try again on the next open or the next daily run. */
    data object Failed : HospitalRefresh
}

/** The copy built into this app version (`assets/hospitals.json`); null until there is one. */
fun interface BuiltInHospitalList {
    fun read(): String?
}

/**
 * The hospital list in use: the built-in copy or the downloaded one, whichever has the higher
 * version (that covers app updates, which may bring a newer list, and "Clear data", which
 * removes the downloaded one). One per process ([HospitalModule]).
 */
class HospitalLists(
    private val builtIn: BuiltInHospitalList,
    private val store: HospitalListStore,
    private val download: HospitalListDownload,
    private val geo: GeoRepository,
    private val clock: Clock,
) : HospitalRepository, HospitalListRefresher {
    private val mutex = Mutex()
    private val refreshing = Mutex()
    private var current: HospitalList? = null

    override suspend fun search(district: String, query: String, limit: Int, near: String?): List<Hospital> =
        HospitalSearch.search(list().hospitals, district, query, limit, near)

    suspend fun list(): HospitalList = mutex.withLock {
        current ?: withContext(Dispatchers.IO) { load() }.also { current = it }
    }

    private fun load(): HospitalList {
        val bundled = builtIn.read()?.let(HospitalSearch::parseOrNull) ?: HospitalList.EMPTY
        val saved = store.readList()?.let { text ->
            HospitalSearch.parseOrNull(text) ?: null.also { store.forget() } // damaged: fetch it again
        }
        // On a tie the built-in copy wins: the same list, already checked by the build's tests.
        return if (saved != null && saved.version > bundled.version) saved else bundled
    }

    override suspend fun refreshIfDue(after: Duration): HospitalRefresh = refreshing.withLock {
        withContext(Dispatchers.IO) {
            val state = store.readState()
            val now = clock.now()
            val last = state.checkedAtMillis?.let(Instant::ofEpochMilli)
            // A clock set back (last check "in the future") counts as due.
            if (last != null && !now.isBefore(last) && Duration.between(last, now) < after) return@withContext HospitalRefresh.NotDue

            when (val reply = download.fetch(state.etag)) {
                HospitalListDownload.Reply.Failed -> HospitalRefresh.Failed
                HospitalListDownload.Reply.NotModified -> {
                    store.writeState(state.copy(checkedAtMillis = now.toEpochMilli()))
                    HospitalRefresh.Unchanged
                }
                is HospitalListDownload.Reply.Downloaded -> {
                    val candidate = HospitalSearch.parseOrNull(reply.text)
                    when (val verdict = HospitalListRules.check(candidate, list(), geo.geo())) {
                        HospitalListRules.Verdict.Accept -> {
                            candidate!!
                            store.writeList(reply.text)
                            store.writeState(HospitalListStore.State(reply.etag, now.toEpochMilli()))
                            mutex.withLock { current = candidate }
                            HospitalRefresh.Updated(candidate.version)
                        }
                        HospitalListRules.Verdict.NotNewer -> {
                            store.writeState(HospitalListStore.State(reply.etag, now.toEpochMilli()))
                            HospitalRefresh.Unchanged
                        }
                        is HospitalListRules.Verdict.Reject -> {
                            // The refused file's ETag isn't kept, so tomorrow's check downloads the
                            // file again; a newer app (with a newer bd-geo) may accept it.
                            store.writeState(state.copy(checkedAtMillis = now.toEpochMilli()))
                            HospitalRefresh.Rejected(verdict.reason)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ASSET = "hospitals.json"
    }
}

/**
 * The downloaded list and when the website last answered, in the app's files (`filesDir`), not
 * its cache: realme's "Clear cache" must not throw the list away.
 */
class HospitalListStore(private val dir: File) {
    @Serializable
    data class State(val etag: String? = null, val checkedAtMillis: Long? = null)

    private val list = File(dir, "hospitals.json")
    private val state = File(dir, "check.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun readList(): String? = list.takeIf { it.isFile }?.readText()

    fun writeList(text: String) = writeAtomically(list, text)

    fun readState(): State =
        runCatching { json.decodeFromString(State.serializer(), state.readText()) }.getOrDefault(State())

    fun writeState(s: State) = writeAtomically(state, json.encodeToString(State.serializer(), s))

    /** Drops a damaged list and its ETag, so the next check downloads the file in full. */
    fun forget() {
        list.delete()
        state.delete()
    }

    /** A crash mid-write leaves the old file whole: write beside it, then rename over it. */
    private fun writeAtomically(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(text)
        // On Android, rename replaces the old file in one step. Windows (unit tests) won't rename
        // over an existing file, so delete it first there.
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("can't save ${target.name}")
        }
    }
}

/**
 * One conditional GET of the website's `hospitals.json`. GitHub Pages answers 304 with no body
 * when the file hasn't changed since [Reply.Downloaded.etag].
 */
class HospitalListDownload(http: OkHttpClient, url: String) {
    sealed interface Reply {
        data object NotModified : Reply

        data class Downloaded(val text: String, val etag: String?) : Reply

        data object Failed : Reply
    }

    private val url: HttpUrl = url.toHttpUrl()

    // No OkHttp cache (the shared client has none, and this says so): Pages sends max-age=600,
    // and a cached copy would hide the 304/200 answer this check relies on. No redirect from
    // https to http either.
    private val http = http.newBuilder().cache(null).followSslRedirects(false).build()

    suspend fun fetch(etag: String?): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        try {
            http.newCall(request).execute().use { response ->
                // Only our own site: the address may have been redirected on the way.
                val from = response.request.url
                if (from.scheme != url.scheme || from.host != url.host || from.port != url.port) return@use Reply.Failed
                when (response.code) {
                    304 -> Reply.NotModified
                    200 -> {
                        val source = response.body.source()
                        if (source.request(MAX_BYTES + 1)) return@use Reply.Failed // over the limit
                        Reply.Downloaded(source.buffer.readUtf8(), response.header("ETag"))
                    }
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
        /** C's website build copies the app repo's `app/src/main/assets/hospitals.json` here. */
        const val URL = "https://liquidlyf.github.io/data/hospitals.json"

        /** B's generator refuses to write a file over 2 MB; the whole country would be well under. */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HospitalModule {
    @Binds abstract fun hospitals(impl: HospitalLists): HospitalRepository

    @Binds abstract fun refresher(impl: HospitalLists): HospitalListRefresher

    companion object {
        @Provides
        @Singleton
        fun lists(
            @ApplicationContext context: Context,
            http: OkHttpClient,
            geo: GeoRepository,
            clock: Clock,
        ): HospitalLists = HospitalLists(
            builtIn = {
                try {
                    context.assets.open(HospitalLists.ASSET).bufferedReader().use { it.readText() }
                } catch (_: FileNotFoundException) {
                    null
                }
            },
            store = HospitalListStore(File(context.filesDir, "hospitals")),
            download = HospitalListDownload(http, HospitalListDownload.URL),
            geo = geo,
            clock = clock,
        )
    }
}
