package io.github.liquidlyf.core.data.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.liquidlyf.core.data.ProcessDataStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which notifications this phone wants (phase-05 5.10). Topics are per phone, so these are too.
 * Off for request alerts = no area topics; off for updates = no `usr_` topic (the inbox still
 * has them); off for reminders = the on-device reminders stay silent.
 */
data class AlertPrefs(
    val requestAlerts: Boolean = true,
    val updates: Boolean = true,
    val reminders: Boolean = true,
)

interface AlertPrefsStore {
    val prefs: Flow<AlertPrefs>

    suspend fun current(): AlertPrefs = prefs.first()

    suspend fun set(prefs: AlertPrefs)
}

@Singleton
class DataStoreAlertPrefsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlertPrefsStore {
    private val dataStore: DataStore<Preferences> by lazy { ProcessDataStores.get(context, "alert_prefs") }

    override val prefs: Flow<AlertPrefs> = dataStore.data.map {
        AlertPrefs(
            requestAlerts = it[REQUEST_ALERTS] ?: true,
            updates = it[UPDATES] ?: true,
            reminders = it[REMINDERS] ?: true,
        )
    }

    override suspend fun set(prefs: AlertPrefs) {
        dataStore.edit {
            it[REQUEST_ALERTS] = prefs.requestAlerts
            it[UPDATES] = prefs.updates
            it[REMINDERS] = prefs.reminders
        }
    }

    private companion object {
        val REQUEST_ALERTS = booleanPreferencesKey("request_alerts")
        val UPDATES = booleanPreferencesKey("updates")
        val REMINDERS = booleanPreferencesKey("reminders")
    }
}
