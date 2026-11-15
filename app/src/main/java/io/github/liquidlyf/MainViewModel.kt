package io.github.liquidlyf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.navigation.GateZone
import io.github.liquidlyf.navigation.TopLevelDestination
import io.github.liquidlyf.navigation.startZone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainUiState {
    /** Settings or the session not read yet; the splash screen stays up. */
    data object Loading : MainUiState

    data class Ready(
        val themeMode: ThemeMode,
        /** Decided once per launch, so the navigation graph never changes under the user. */
        val startZone: GateZone,
        val initialTab: TopLevelDestination?,
        /** Live: the auth gate moves the user when it changes. */
        val session: SessionState,
    ) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    // Eagerly: the start destination must survive the activity going to the background.
    /** A20: "Signing out…" over the whole app while sign-out runs. */
    val signingOut: StateFlow<Boolean> = sessionManager.signingOut

    val uiState: StateFlow<MainUiState> = combine(settingsRepository.settings, sessionManager.state, ::Pair)
        .scan<_, MainUiState>(MainUiState.Loading) { previous, (settings, session) ->
            when (previous) {
                MainUiState.Loading ->
                    if (session == SessionState.Loading) {
                        MainUiState.Loading
                    } else {
                        val start = startZone(session, settings.onboardingSeen)
                        MainUiState.Ready(
                            themeMode = settings.themeMode,
                            startZone = start,
                            // Only when the app opens signed in: after signing in or up, it starts on Home.
                            initialTab = if (start == GateZone.MAIN) TopLevelDestination.fromName(settings.lastSelectedTab) else null,
                            session = session,
                        )
                    }
                is MainUiState.Ready -> previous.copy(themeMode = settings.themeMode, session = session)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)

    init {
        // Signed out (from any screen, or the account is gone): the next person on this phone starts on Home.
        viewModelScope.launch {
            sessionManager.state.filter { it == SessionState.SignedOut }.collect {
                settingsRepository.setLastSelectedTab(TopLevelDestination.HOME.name)
            }
        }
    }

    fun onOnboardingFinished() {
        viewModelScope.launch { settingsRepository.setOnboardingSeen(true) }
    }

    fun onTabSelected(tab: TopLevelDestination) {
        viewModelScope.launch { settingsRepository.setLastSelectedTab(tab.name) }
    }

    /** "Retry" on the can't-load-your-profile screen. */
    fun retrySession() = sessionManager.refresh()

    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }
}
