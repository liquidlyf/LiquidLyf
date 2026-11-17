package io.github.liquidlyf.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.AppLanguageStore
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 8.1: the app's own language. */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** Change password is hidden for Google-only accounts. */
    val hasPassword: Boolean = false,
    /** The admin claim: shows Moderation (7.5). */
    val admin: Boolean = false,
    /** 8.1 Privacy: "List me as a donor". Null until the profile is read (the switches stay hidden). */
    val listed: Boolean? = null,
    val available: Boolean = false,
    /** P1: without a blood group nobody can be listed (the rules insist). */
    val bloodGroup: BloodGroup? = null,
    /** A privacy switch is being saved. */
    val saving: Boolean = false,
    /** While resting: when alerts start again, so "Ready to donate" says it (COPY-04). */
    val alertsAgainOn: Instant? = null,
    /** The sign-out check names the account (N5). */
    val email: String? = null,
)

sealed interface SettingsEvent {
    data class Message(@StringRes val text: Int) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    auth: AuthRepository,
    private val languages: AppLanguageStore,
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val eligibility: EligibilityCalculator = EligibilityCalculator(),
    private val clock: Clock = Clock.SYSTEM,
) : ViewModel() {
    private val language = MutableStateFlow(languages.current())
    private val saving = MutableStateFlow(false)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val local = combine(language, saving, ::Pair)

    val uiState: StateFlow<SettingsUiState> =
        combine(settingsRepository.settings, auth.authState, auth.claims, session.state, local) { settings, user, claims, s, (lang, busy) ->
            val profile = (s as? SessionState.Ready)?.profile
            SettingsUiState(
                themeMode = settings.themeMode,
                language = lang,
                hasPassword = user?.hasPassword == true,
                admin = user != null && claims.uid == user.uid && claims.admin,
                listed = profile?.showInDonorSearch,
                available = profile?.available == true,
                bloodGroup = profile?.bloodGroup,
                saving = busy,
                alertsAgainOn = profile?.let { p ->
                    val e = eligibility.evaluate(p.birthYear, p.weightOk, p.gender, p.lastDonationAt, clock.now())
                    e.eligibleFrom.takeIf { IneligibleReason.REST_PERIOD in e.reasons }
                },
                email = user?.email,
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /** The screens restart in the new language (this view model survives it). */
    fun setLanguage(choice: AppLanguage) {
        if (choice == language.value) return
        language.value = choice
        languages.set(choice)
    }

    /** On: after the consent dialog. Off: withdraws consent; the public card goes and area alerts stop. */
    fun setListed(listed: Boolean) = save { p -> profiles.setListed(p, listed) }

    fun setAvailable(available: Boolean) = save { p -> profiles.setAvailable(p, available) }

    private fun save(write: suspend (UserProfile) -> AppResult<Unit>) {
        val p = (session.state.value as? SessionState.Ready)?.profile ?: return
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            when (val result = write(p)) {
                // The session re-reads the profile; the alert topics follow it (PushTopicManager).
                is AppResult.Success -> session.refresh()
                is AppResult.Failure -> events.send(SettingsEvent.Message(result.error.messageRes))
            }
            saving.value = false
        }
    }
}
