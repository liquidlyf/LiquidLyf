package io.github.liquidlyf.core.data.link

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.ProcessDataStores
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D31: a shared request's link, https://liquidlyf.github.io/r/#{id}. The ID is after the #, which
 * never leaves the phone, so GitHub sees that the page was opened, not which request (C's point).
 * The web page (C's /r/) is for people without the app; with it, the app opens (App Links, verified
 * once the site serves assetlinks.json with the release key). A hand-made /r/?id={id} works too.
 */
object RequestLinks {
    private const val HOST = "liquidlyf.github.io"
    private val PATHS = setOf("/r", "/r/", "/r/index.html")

    /** A Firestore ID: 20 letters and digits. Anything else is ignored, never looked up. */
    private val ID = Regex("[A-Za-z0-9]{20}")

    fun of(id: String): String = "https://$HOST/r/#$id"

    /** The request ID in such a link, or null for any other link. */
    fun idFrom(link: String?): String? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != HOST || uri.path !in PATHS) return null
        val fromQuery = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "id" }?.get(1)
        }
        return uri.rawFragment?.takeIf(ID::matches) ?: fromQuery?.takeIf(ID::matches)
    }

    /** Also the app's own liquidlyf://request/{id}: the web page's button, and the alerts' taps. */
    fun idFromAny(link: String?): String? = idFrom(link) ?: run {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "liquidlyf" || uri.host != "request") return null
        uri.path?.removePrefix("/")?.takeIf(ID::matches)
    }
}

/**
 * D31: a request link opened before the person is in the app (signed out, or still confirming the
 * email or setting up) is kept on this phone, not in memory: it must survive leaving the app for the
 * email and the setup steps. It's opened once they reach the app, then forgotten; one older than 3
 * days is dropped, so an old link doesn't pop up next week.
 */
interface PendingRequestStore {
    /** The kept request's ID while it's fresh, or null. */
    val pending: Flow<String?>

    suspend fun keep(id: String)

    /** The kept request once: it's forgotten as it's taken. Null if none, or too old. */
    suspend fun take(): String?

    /** At each start: one older than 3 days is deleted, not only ignored. */
    suspend fun dropIfOld()

    companion object {
        val KEEP_FOR: Duration = Duration.ofDays(3)

        fun fresh(savedAt: Instant, now: Instant): Boolean = now.isBefore(savedAt.plus(KEEP_FOR))
    }
}

@Singleton
class DataStorePendingRequestStore internal constructor(
    open: () -> DataStore<Preferences>,
    private val clock: Clock,
) : PendingRequestStore {
    @Inject constructor(@ApplicationContext context: Context, clock: Clock) : this({ ProcessDataStores.get(context, "pending_request") }, clock)

    private val dataStore: DataStore<Preferences> by lazy(open)

    override val pending: Flow<String?> = dataStore.data.map { it.freshId() }

    /** The newest link wins: a second one before the first is shown replaces it. */
    override suspend fun keep(id: String) {
        dataStore.edit {
            it[ID] = id
            it[SAVED_AT] = clock.now().toEpochMilli()
        }
    }

    override suspend fun take(): String? {
        var taken: String? = null
        dataStore.edit {
            taken = it.freshId()
            it.remove(ID)
            it.remove(SAVED_AT)
        }
        return taken
    }

    override suspend fun dropIfOld() {
        dataStore.edit {
            if (it[ID] != null && it.freshId() == null) {
                it.remove(ID)
                it.remove(SAVED_AT)
            }
        }
    }

    private fun Preferences.freshId(): String? {
        val id = this[ID] ?: return null
        val savedAt = this[SAVED_AT]?.let(Instant::ofEpochMilli) ?: return null
        return id.takeIf { PendingRequestStore.fresh(savedAt, clock.now()) }
    }

    private companion object {
        val ID = stringPreferencesKey("id")
        val SAVED_AT = longPreferencesKey("saved_at")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PendingRequestModule {
    @Binds abstract fun pendingRequests(impl: DataStorePendingRequestStore): PendingRequestStore
}
