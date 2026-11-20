package io.github.liquidlyf.core.data.auth

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
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** D34: the address a "Change email" on this phone asked for, and when. */
data class RequestedEmail(val email: String, val askedAt: Instant) {
    /** Used and shown for 3 days; after that it's deleted at the next start. */
    fun freshAt(now: Instant): Boolean = now.isBefore(askedAt.plus(KEEP_FOR))

    companion object {
        val KEEP_FOR: Duration = Duration.ofDays(3)
    }
}

/**
 * D34 (and D32): the asked-for address is kept on this phone, not in memory. Android often ends the
 * app while the person is in their email opening the link; without it, Edit profile's "waiting to
 * confirm" line, the check at every return and Sign in's "Your email was changed" would be lost.
 * Private to the app and out of backups, like everything it stores. Cleared when the change shows,
 * when a new "Change email" replaces it, at sign-out, and 3 days after the request.
 */
interface RequestedEmailStore {
    /** Kept as stored; [RequestedEmail.freshAt] says whether it's still used. */
    val requested: StateFlow<RequestedEmail?>

    /** A new request replaces the old one: the newest address is shown. */
    suspend fun keep(email: String)

    suspend fun clear()

    /** At each start: one older than 3 days is deleted. */
    suspend fun dropIfOld()
}

@Singleton
class DataStoreRequestedEmailStore internal constructor(
    open: () -> DataStore<Preferences>,
    private val clock: Clock,
    scope: CoroutineScope,
) : RequestedEmailStore {
    @Inject constructor(@ApplicationContext context: Context, clock: Clock, @ApplicationScope scope: CoroutineScope) :
        this({ ProcessDataStores.get(context, "requested_email") }, clock, scope)

    private val dataStore: DataStore<Preferences> by lazy(open)

    override val requested: StateFlow<RequestedEmail?> = dataStore.data
        .map { p -> p[EMAIL]?.let { email -> p[ASKED_AT]?.let { RequestedEmail(email, Instant.ofEpochMilli(it)) } } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun keep(email: String) {
        dataStore.edit {
            it[EMAIL] = email
            it[ASKED_AT] = clock.now().toEpochMilli()
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(EMAIL)
            it.remove(ASKED_AT)
        }
    }

    override suspend fun dropIfOld() {
        dataStore.edit {
            val askedAt = it[ASKED_AT]?.let(Instant::ofEpochMilli)
            if (it[EMAIL] != null && (askedAt == null || !RequestedEmail(it[EMAIL]!!, askedAt).freshAt(clock.now()))) {
                it.remove(EMAIL)
                it.remove(ASKED_AT)
            }
        }
    }

    private companion object {
        val EMAIL = stringPreferencesKey("email")
        val ASKED_AT = longPreferencesKey("asked_at")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RequestedEmailModule {
    @Binds abstract fun requestedEmails(impl: DataStoreRequestedEmailStore): RequestedEmailStore
}
