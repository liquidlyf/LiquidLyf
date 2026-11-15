package io.github.liquidlyf.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Change password is hidden for Google-only accounts. */
    val hasPassword: Boolean = false,
    /** The admin claim: shows Moderation (7.5). */
    val admin: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    auth: AuthRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(settingsRepository.settings, auth.authState, auth.claims) { settings, user, claims ->
        SettingsUiState(
            themeMode = settings.themeMode,
            hasPassword = user?.hasPassword == true,
            admin = user != null && claims.uid == user.uid && claims.admin,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }
}
