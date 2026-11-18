package io.github.liquidlyf.feature.account

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.account.AccountDeleter
import io.github.liquidlyf.core.data.account.DeletionStep
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Change password (3.11) ----

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    @StringRes val currentError: Int? = null,
    @StringRes val newError: Int? = null,
    @StringRes val formError: Int? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
)

/** Re-authenticate with the current password, then set the new one. Hidden for Google-only accounts. */
@HiltViewModel
class ChangePasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = state.asStateFlow()

    fun onCurrentChange(v: String) = state.update { it.copy(current = v, currentError = null, formError = null) }

    fun onNewChange(v: String) = state.update { it.copy(new = v, newError = null, formError = null) }

    fun save() {
        val s = state.value
        if (s.saving) return
        val currentError = if (s.current.isEmpty()) R.string.error_password_required else null
        val newError = if (s.new.length < Validators.PASSWORD_MIN) R.string.error_password_short else null
        if (currentError != null || newError != null) {
            state.update { it.copy(currentError = currentError, newError = newError) }
            return
        }
        state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            val reauth = auth.reauthenticate(s.current)
            if (reauth is AppResult.Failure) {
                state.update {
                    if (reauth.error == AppError.InvalidCredentials) {
                        it.copy(saving = false, currentError = reauth.error.messageRes)
                    } else {
                        it.copy(saving = false, formError = reauth.error.messageRes)
                    }
                }
                return@launch
            }
            when (val result = auth.changePassword(s.new)) {
                is AppResult.Success -> state.update { it.copy(saving = false, done = true, current = "", new = "") }
                is AppResult.Failure -> state.update {
                    if (result.error == AppError.WeakPassword) {
                        it.copy(saving = false, newError = result.error.messageRes)
                    } else {
                        it.copy(saving = false, formError = result.error.messageRes)
                    }
                }
            }
        }
    }
}

// ---- Delete account: confirm (3.12) ----

data class DeleteAccountUiState(
    val word: String = "",
    val password: String = "",
    /** Google-only accounts have no password to re-enter. */
    val needsPassword: Boolean = true,
    val wordError: Boolean = false,
    @StringRes val passwordError: Int? = null,
    @StringRes val formError: Int? = null,
    val working: Boolean = false,
)

/**
 * Explains what goes, asks for the confirmation word and the password (re-authentication), then
 * starts the deletion. The session then moves everyone to the deleting screen, which runs it and
 * resumes it after a kill.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val deleter: AccountDeleter,
    private val session: SessionManager,
) : ViewModel() {
    private val state = MutableStateFlow(DeleteAccountUiState(needsPassword = auth.authState.value?.hasPassword != false))
    val uiState: StateFlow<DeleteAccountUiState> = state.asStateFlow()

    fun onWordChange(v: String) = state.update { it.copy(word = v, wordError = false) }

    fun onPasswordChange(v: String) = state.update { it.copy(password = v, passwordError = null, formError = null) }

    /** A23: the confirmation word is DELETE (any case) or its Bangla, মুছুন, whatever the app's language. */
    fun confirm() {
        val s = state.value
        val uid = auth.authState.value?.uid ?: return
        if (s.working) return
        val typed = s.word.trim()
        val wordOk = typed.equals(DELETE_WORD, ignoreCase = true) || typed == DELETE_WORD_BN
        val passwordError = if (s.needsPassword && s.password.isEmpty()) R.string.error_password_required else null
        if (!wordOk || passwordError != null) {
            state.update { it.copy(wordError = !wordOk, passwordError = passwordError) }
            return
        }
        state.update { it.copy(working = true, formError = null) }
        viewModelScope.launch {
            if (s.needsPassword) {
                val reauth = auth.reauthenticate(s.password)
                if (reauth is AppResult.Failure) {
                    state.update {
                        if (reauth.error == AppError.InvalidCredentials) {
                            it.copy(working = false, passwordError = reauth.error.messageRes)
                        } else {
                            it.copy(working = false, formError = reauth.error.messageRes)
                        }
                    }
                    return@launch
                }
            }
            deleter.start(uid)
            session.refresh()
        }
    }
}

// ---- Delete account: running and resuming ----

data class DeletingUiState(
    val step: DeletionStep? = null,
    val running: Boolean = true,
    @StringRes val error: Int? = null,
    /** Firebase wants a fresh sign-in before deleting the account itself. */
    val needsPassword: Boolean = false,
    val password: String = "",
)

@HiltViewModel
class DeletingAccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val deleter: AccountDeleter,
    private val session: SessionManager,
) : ViewModel() {
    private val state = MutableStateFlow(DeletingUiState())
    val uiState: StateFlow<DeletingUiState> = state.asStateFlow()

    init {
        run()
    }

    fun onPasswordChange(v: String) = state.update { it.copy(password = v, error = null) }

    /** Retry, or continue after entering the password. */
    fun run() {
        val uid = (session.state.value as? SessionState.DeletingAccount)?.user?.uid ?: auth.authState.value?.uid ?: return
        val password = state.value.password
        state.update { it.copy(running = true, error = null) }
        viewModelScope.launch {
            if (state.value.needsPassword) {
                val reauth = auth.reauthenticate(password)
                if (reauth is AppResult.Failure) {
                    state.update { it.copy(running = false, error = reauth.error.messageRes) }
                    return@launch
                }
            }
            val result = deleter.run(uid) { step -> state.update { it.copy(step = step) } }
            if (result is AppResult.Failure) {
                state.update {
                    it.copy(
                        running = false,
                        error = result.error.messageRes,
                        needsPassword = result.error == AppError.RecentLoginRequired && auth.authState.value?.hasPassword == true,
                    )
                }
            }
            // On success the account is gone: the session signs out and the gate shows sign-in.
        }
    }

    /** Google-only accounts can't re-enter a password: sign in again, and the deletion resumes. */
    fun signOutToSignInAgain() {
        viewModelScope.launch { session.signOut() }
    }
}

// ---- Edit profile (3.10) ----

data class EditProfileUiState(
    /** Counts failed taps on Save: the screen brings the first error into view (FLOW-04). */
    val errorAttempt: Int = 0,
    val fullName: String = "",
    val gender: Gender? = null,
    val birthYear: String = "",
    val bloodGroup: BloodGroup? = null,
    val division: String? = null,
    val district: String? = null,
    val upazila: String? = null,
    val phone: String = "",
    val email: String = "",
    val geo: BdGeo? = null,
    @StringRes val nameError: Int? = null,
    @StringRes val birthYearError: Int? = null,
    @StringRes val phoneError: Int? = null,
    val locationError: Boolean = false,
    @StringRes val formError: Int? = null,
    val saving: Boolean = false,
    /** A blood-group change waits for "Are you sure?". */
    val confirmBloodGroup: Boolean = false,
    val saved: Boolean = false,
    @StringRes val message: Int? = null,
    val messageArg: String? = null,
    /** Answered the donor questions (D1): only then are gender and birth year asked here. */
    val isDonor: Boolean = true,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val geoRepository: GeoRepository,
    private val clock: Clock,
) : ViewModel() {
    private val profile: UserProfile? = (session.state.value as? SessionState.Ready)?.profile
    private val state = MutableStateFlow(
        profile?.let { p ->
            EditProfileUiState(
                fullName = p.fullName, gender = p.gender, birthYear = p.birthYear?.toString().orEmpty(), bloodGroup = p.bloodGroup,
                isDonor = p.hasDonorDetails,
                division = p.area.division, district = p.area.district, upazila = p.area.upazila,
                phone = "0" + p.phone.removePrefix("+880"), email = p.email,
            )
        } ?: EditProfileUiState(),
    )
    val uiState: StateFlow<EditProfileUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
    }

    fun edit(change: EditProfileUiState.() -> EditProfileUiState) = state.update {
        it.change().copy(nameError = null, birthYearError = null, phoneError = null, locationError = false, formError = null)
    }

    fun onDivision(code: String) = edit { copy(division = code, district = null, upazila = null) }

    fun onDistrict(code: String) = edit { copy(district = code, upazila = null) }

    fun save() {
        val s = state.value
        val before = profile ?: return
        if (s.saving) return
        val year = s.birthYear.trim().toIntOrNull()
        val phone = Validators.normalizePhone(s.phone)
        val area = if (s.division != null && s.district != null && s.upazila != null) Area(s.division, s.district, s.upazila) else null
        val errors = s.copy(
            nameError = if (Validators.isValidName(s.fullName)) null else R.string.error_name_invalid,
            // D1: gender and birth year are donor questions; a requester's profile has neither.
            birthYearError = if (!s.isDonor || (year != null && Validators.isValidBirthYear(year, clock.now()))) null else R.string.error_birth_year,
            phoneError = if (phone != null) null else R.string.error_phone_invalid,
            locationError = area == null || s.geo?.isValid(area) != true,
        )
        if (errors.nameError != null || errors.birthYearError != null || errors.phoneError != null || errors.locationError) {
            state.value = errors.copy(errorAttempt = s.errorAttempt + 1)
            return
        }
        // Changing a known group asks first; adding one for the first time (P1) doesn't.
        if (before.bloodGroup != null && s.bloodGroup != before.bloodGroup && !s.confirmBloodGroup) {
            state.update { it.copy(confirmBloodGroup = true) }
            return
        }
        val draft = ProfileDraft(
            fullName = s.fullName, phone = phone!!, bloodGroup = s.bloodGroup ?: before.bloodGroup,
            gender = if (s.isDonor) s.gender ?: before.gender else null, birthYear = if (s.isDonor) year else null,
            weightOk = before.weightOk, area = area!!,
            available = before.available, lastDonationAt = before.lastDonationAt,
            showInDonorSearch = before.showInDonorSearch, locale = before.locale, termsVersion = before.termsVersion,
        )
        state.update { it.copy(saving = true, confirmBloodGroup = false) }
        viewModelScope.launch {
            when (val result = profiles.update(before, draft)) {
                is AppResult.Success -> {
                    // The session re-reads the profile; topics follow a new area or blood group.
                    session.refresh()
                    state.update { it.copy(saving = false, saved = true) }
                }
                is AppResult.Failure -> state.update { it.copy(saving = false, formError = result.error.messageRes) }
            }
        }
    }

    /** "Are you sure?" answered: save with the new blood group, or put the old one back. */
    fun onBloodGroupConfirmed(confirmed: Boolean) {
        if (confirmed) {
            state.update { it.copy(confirmBloodGroup = true) }
            save()
        } else {
            state.update { it.copy(confirmBloodGroup = false, bloodGroup = profile?.bloodGroup) }
        }
    }

    fun changeEmail(newEmail: String) {
        if (!Validators.isValidEmail(newEmail)) {
            state.update { it.copy(message = R.string.error_invalid_email, messageArg = null) }
            return
        }
        viewModelScope.launch {
            val result = auth.changeEmail(newEmail)
            state.update {
                if (result is AppResult.Failure) {
                    it.copy(message = result.error.messageRes, messageArg = null)
                } else {
                    it.copy(message = R.string.change_email_sent, messageArg = newEmail.trim())
                }
            }
        }
    }

    fun onMessageShown() = state.update { it.copy(message = null, messageArg = null) }
}

/** The account-deletion confirmation words (A23). The screen shows DELETE; Bangla names both. */
internal const val DELETE_WORD = "DELETE"
internal const val DELETE_WORD_BN = "মুছুন"
