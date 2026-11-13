package io.github.liquidlyf.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        // A damaged file must not crash the app: fall back to the defaults.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserSettings(
                themeMode = prefs[Keys.THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.SYSTEM,
                onboardingSeen = prefs[Keys.ONBOARDING_SEEN] ?: false,
                lastSelectedTab = prefs[Keys.LAST_SELECTED_TAB],
            )
        }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setOnboardingSeen(seen: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_SEEN] = seen }
    }

    override suspend fun setLastSelectedTab(tab: String) {
        dataStore.edit { it[Keys.LAST_SELECTED_TAB] = tab }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val LAST_SELECTED_TAB = stringPreferencesKey("last_selected_tab")
    }
}
