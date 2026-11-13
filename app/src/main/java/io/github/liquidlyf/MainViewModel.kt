package io.github.liquidlyf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.navigation.TopLevelDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainUiState {
    /** Settings not read yet; the splash screen stays up. */
    data object Loading : MainUiState

    data class Ready(
        val themeMode: ThemeMode,
        /** Decided once per launch, so the navigation graph never changes under the user. */
        val startWithOnboarding: Boolean,
        val initialTab: TopLevelDestination?,
    ) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // Eagerly: the start destination must survive the activity going to the background.
    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .scan<_, MainUiState>(MainUiState.Loading) { previous, settings ->
            when (previous) {
                MainUiState.Loading -> MainUiState.Ready(
                    themeMode = settings.themeMode,
                    startWithOnboarding = !settings.onboardingSeen,
                    initialTab = TopLevelDestination.fromName(settings.lastSelectedTab),
                )
                is MainUiState.Ready -> previous.copy(themeMode = settings.themeMode)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)

    fun onOnboardingFinished() {
        viewModelScope.launch { settingsRepository.setOnboardingSeen(true) }
    }

    fun onTabSelected(tab: TopLevelDestination) {
        viewModelScope.launch { settingsRepository.setLastSelectedTab(tab.name) }
    }
}
