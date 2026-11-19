package io.github.liquidlyf.core.data.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.liquidlyf.core.data.ProcessDataStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One request alert this device showed (topic push, doc 10 §6.3). Public fields only, the same as
 * the push itself. The inbox lists these next to the personal Firestore inbox (phase-05 5.9).
 */
@Serializable
data class LocalAlert(
    val requestId: String,
    /** True for the second alert of a request: "Still needed". */
    val stillNeeded: Boolean,
    val bloodGroup: String? = null,
    val district: String? = null,
    val urgency: String? = null,
    val receivedAtMillis: Long,
)

/**
 * What the push service remembers on this device: which requests already alerted (max 2 each),
 * which requests are mine (no alert for my own), and the recent alerts for the inbox. All of it
 * is cleared on sign-out.
 */
interface AlertStore {
    /** Newest first. */
    val alerts: Flow<List<LocalAlert>>

    /** How many alerts this device already showed for [requestId]. */
    suspend fun shownCount(requestId: String): Int

    suspend fun recordShown(alert: LocalAlert)

    /**
     * D20: when the newest shown personal push came that has an inbox item (an offer, a confirm…).
     * Home's bell lights up from it at once, with no extra read. Null if none since sign-in.
     */
    val lastPersonalAtMillis: Flow<Long?>

    suspend fun recordPersonal(receivedAtMillis: Long)

    /** Called when I post a request: its alerts are for others. */
    suspend fun rememberMyRequest(requestId: String)

    suspend fun isMyRequest(requestId: String): Boolean

    suspend fun clear()
}

/** Clears the app's notifications (on sign-out, so nobody else on the phone opens them). */
interface SystemNotifications {
    fun cancelAll()
}

@Serializable
private data class AlertState(
    val alerts: List<LocalAlert> = emptyList(),
    /** requestId → alerts shown, oldest first. */
    val shown: Map<String, Int> = emptyMap(),
    val mine: List<String> = emptyList(),
    val personalAtMillis: Long? = null,
)

/** One small JSON value in its own DataStore file. */
@Singleton
class DataStoreAlertStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlertStore {
    private val dataStore: DataStore<Preferences> by lazy { ProcessDataStores.get(context, "push_alerts") }
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String?): AlertState =
        raw?.let { runCatching { json.decodeFromString(AlertState.serializer(), it) }.getOrNull() } ?: AlertState()

    private suspend fun state(): AlertState = decode(dataStore.data.first()[STATE])

    private suspend fun update(change: (AlertState) -> AlertState) {
        dataStore.edit { prefs -> prefs[STATE] = json.encodeToString(AlertState.serializer(), change(decode(prefs[STATE]))) }
    }

    override val alerts: Flow<List<LocalAlert>> = dataStore.data.map { decode(it[STATE]).alerts }

    override suspend fun shownCount(requestId: String): Int = state().shown[requestId] ?: 0

    override suspend fun recordShown(alert: LocalAlert) = update { s ->
        val count = (s.shown[alert.requestId] ?: 0) + 1
        s.copy(
            alerts = (listOf(alert) + s.alerts).take(MAX_ALERTS),
            // Re-insert at the end so the oldest requests drop off first.
            shown = (s.shown - alert.requestId + (alert.requestId to count)).entries.toList().takeLast(MAX_REQUESTS).associate { it.key to it.value },
        )
    }

    override val lastPersonalAtMillis: Flow<Long?> = dataStore.data.map { decode(it[STATE]).personalAtMillis }.distinctUntilChanged()

    override suspend fun recordPersonal(receivedAtMillis: Long) = update { s ->
        s.copy(personalAtMillis = maxOf(s.personalAtMillis ?: 0L, receivedAtMillis))
    }

    override suspend fun rememberMyRequest(requestId: String) = update { s ->
        s.copy(mine = (s.mine - requestId + requestId).takeLast(MAX_MINE))
    }

    override suspend fun isMyRequest(requestId: String): Boolean = requestId in state().mine

    override suspend fun clear() {
        dataStore.edit { it.remove(STATE) }
    }

    private companion object {
        val STATE = stringPreferencesKey("state")
        const val MAX_ALERTS = 50
        const val MAX_REQUESTS = 200
        const val MAX_MINE = 50
    }
}
