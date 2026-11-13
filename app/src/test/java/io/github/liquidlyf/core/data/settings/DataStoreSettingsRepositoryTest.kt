package io.github.liquidlyf.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/*
 * Uses an in-memory DataStore. DataStore's real file storage can't replace its file on a
 * Windows JVM (it works on Android and on Linux CI), and the file I/O is DataStore's own job.
 */
class DataStoreSettingsRepositoryTest {

    @Test
    fun `defaults before anything is saved`() = runTest {
        val repository = DataStoreSettingsRepository(InMemoryDataStore())

        assertEquals(UserSettings(), repository.settings.first())
    }

    @Test
    fun `saved values are read back`() = runTest {
        val repository = DataStoreSettingsRepository(InMemoryDataStore())

        repository.setThemeMode(ThemeMode.DARK)
        repository.setOnboardingSeen(true)
        repository.setLastSelectedTab("PROFILE")

        assertEquals(
            UserSettings(themeMode = ThemeMode.DARK, onboardingSeen = true, lastSelectedTab = "PROFILE"),
            repository.settings.first(),
        )
    }

    @Test
    fun `an unknown stored theme falls back to system`() = runTest {
        val dataStore = InMemoryDataStore()
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "SEPIA" }

        assertEquals(ThemeMode.SYSTEM, DataStoreSettingsRepository(dataStore).settings.first().themeMode)
    }

    @Test
    fun `an unreadable settings file gives the defaults instead of a crash`() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("corrupt file") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = emptyPreferences()
        }

        assertEquals(UserSettings(), DataStoreSettingsRepository(broken).settings.first())
    }
}

private class InMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
