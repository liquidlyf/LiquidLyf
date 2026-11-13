package io.github.liquidlyf.core.data.settings

import kotlinx.coroutines.flow.Flow

/** Light, dark, or follow the phone's setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** On-device preferences. Never backed up or moved to another phone (BUG-040). */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingSeen: Boolean = false,
    /** Name of the bottom-navigation tab last opened, or null if none yet. */
    val lastSelectedTab: String? = null,
)

interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setOnboardingSeen(seen: Boolean)

    suspend fun setLastSelectedTab(tab: String)
}
