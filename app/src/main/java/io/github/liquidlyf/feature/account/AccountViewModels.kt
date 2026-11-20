package io.github.liquidlyf.feature.account

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.GoogleAuthProvider
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
import io.github.liquidlyf.core.designsystem.birthYearErrorRes
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.NewPassword
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.auth.VerifyEmailViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Change password (3.11) ----

data class ChangePasswordUiState(
    val current: String = "",
    /** D24: typed twice; it decides when each box is grey, green or red. */
    val newPassword: NewPassword = NewPassword(),
    @StringRes val currentError: Int? = null,
    /** Firebase's own "weak password"; the 8-character rule is [newPassword]'s. */
    @StringRes val newError: Int? = null,
    @StringRes val formError: Int? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
    /** Low 25: where "Forgot your password?" sent the reset link. */
    val resetSentTo: String? = null,
    /** Counts failed taps on Save: the screen brings the first red box into view. */
    val errorAttempt: Int = 0,
)

/** Re-authenticate with the current password, then set the new one. Hidden for Google-only accounts. */
@HiltViewModel
class ChangePasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = state.asStateFlow()

    fun onCurrentChange(v: String) = state.update { it.copy(current = v, currentError = null, formError = null) }

    fun onNewChange(v: String) = state.update { it.copy(newPassword = it.newPassword.type(v), newError = null, formError = null) }

    fun onNewAgainChange(v: String) = state.update { it.copy(newPassword = it.newPassword.typeAgain(v), formError = null) }

    fun onNewFocus(focused: Boolean) = state.update { it.copy(newPassword = it.newPassword.focusValue(focused)) }

    fun onNewAgainFocus(focused: Boolean) = state.update { it.copy(newPassword = it.newPassword.focusAgain(focused)) }

    /** Low 25: forgot the current password: the reset link to this account's email. */
    fun sendReset() {
        val email = auth.authState.value?.email ?: return
        if (state.value.saving) return
        state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            val result = auth.sendPasswordReset(email)
            state.update {
                if (result is AppResult.Failure) it.copy(saving = false, formError = result.error.messageRes) else it.copy(saving = false, resetSentTo = email)
            }
        }
    }

    fun save() {
        val s = state.value
        if (s.saving) return
        val currentError = if (s.current.isEmpty()) R.string.error_password_required else null
        if (currentError != null || !s.newPassword.isValid) {
            state.update { it.copy(currentError = currentError, newPassword = it.newPassword.check(), errorAttempt = it.errorAttempt + 1) }
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
            when (val result = auth.changePassword(s.newPassword.value)) {
                is AppResult.Success -> state.update { it.copy(saving = false, done = true, current = "", newPassword = NewPassword()) }
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
    /** The group before this edit, for "From B+ to A+" (D's part 2, Low 15). */
    val previousBloodGroup: BloodGroup? = null,
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
    /** D30: signs in with Google only (no password): no Change email, Google keeps its own address. */
    val googleOnly: Boolean = false,
    /** D34: the address "Change email" asked for, until its link is opened (3 days at most). */
    val pendingEmail: String? = null,
    /** D34: seconds until "Resend email" works again (the confirm-email page's 60). */
    val resendIn: Int = 0,
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
    /** D23 a: "Do you weigh at least 45 kg?", which a donor can now change here. */
    val weightOk: Boolean? = null,
    /** D23 a: the answer went from না to হ্যাঁ: after Save, "list me" is asked again. */
    val askListMe: Boolean = false,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val geoRepository: GeoRepository,
    private val clock: Clock,
    private val eligibility: EligibilityCalculator,
) : ViewModel() {
    private val profile: UserProfile? = (session.state.value as? SessionState.Ready)?.profile
    private val state = MutableStateFlow(
        profile?.let { p ->
            EditProfileUiState(
                fullName = p.fullName, gender = p.gender, birthYear = p.birthYear?.toString().orEmpty(), bloodGroup = p.bloodGroup,
                isDonor = p.hasDonorDetails, weightOk = p.weightOk,
                division = p.area.division, district = p.area.district, upazila = p.area.upazila,
                phone = "0" + p.phone.removePrefix("+880"), email = p.email,
                googleOnly = auth.authState.value?.let { !it.hasPassword && GoogleAuthProvider.PROVIDER_ID in it.providers } == true,
            )
        } ?: EditProfileUiState(),
    )
    val uiState: StateFlow<EditProfileUiState> = state.asStateFlow()
    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
        // D34: the waiting address, and the email as it is now (it changes while this page is open
        // when the person comes back from the link).
        viewModelScope.launch {
            auth.requestedEmail.collect { r -> state.update { it.copy(pendingEmail = r?.takeIf { x -> x.freshAt(clock.now()) }?.email) } }
        }
        viewModelScope.launch {
            auth.authState.mapNotNull { it?.email }.distinctUntilChanged().collect { email -> state.update { it.copy(email = email) } }
        }
    }

    /** D34: "Resend email" on the waiting line: the same link again, to the same address. */
    fun resendEmailChange() {
        val pending = state.value.pendingEmail ?: return
        if (state.value.resendIn > 0) return
        startCooldown()
        viewModelScope.launch {
            val result = auth.changeEmail(pending)
            state.update {
                it.copy(message = if (result is AppResult.Failure) result.error.messageRes else R.string.verify_email_sent, messageArg = null)
            }
        }
    }

    private fun startCooldown() {
        countdown?.cancel()
        state.update { it.copy(resendIn = RESEND_WAIT_SECONDS) }
        countdown = viewModelScope.launch {
            while (state.value.resendIn > 0) {
                delay(1_000)
                state.update { it.copy(resendIn = it.resendIn - 1) }
            }
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
            birthYearError = if (!s.isDonor) null else birthYearErrorRes(s.birthYear, clock.now()),
            phoneError = if (phone != null) null else R.string.error_phone_invalid,
            locationError = area == null || s.geo?.isValid(area) != true,
        )
        if (errors.nameError != null || errors.birthYearError != null || errors.phoneError != null || errors.locationError) {
            state.value = errors.copy(errorAttempt = s.errorAttempt + 1)
            return
        }
        // Changing a known group asks first; adding one for the first time (P1) doesn't.
        if (before.bloodGroup != null && s.bloodGroup != before.bloodGroup && !s.confirmBloodGroup) {
            state.update { it.copy(confirmBloodGroup = true, previousBloodGroup = before.bloodGroup) }
            return
        }
        val weightOk = if (s.isDonor) s.weightOk ?: before.weightOk else null
        // D23 a: weight না or age over 60 can't stay listed; the same save unlists (the rules insist).
        val block = if (year != null && weightOk != null) eligibility.listingBlock(year, weightOk, clock.now()) else null
        val listed = before.showInDonorSearch && block == null
        val group = s.bloodGroup ?: before.bloodGroup
        val draft = ProfileDraft(
            fullName = s.fullName, phone = phone!!, bloodGroup = group,
            gender = if (s.isDonor) s.gender ?: before.gender else null, birthYear = if (s.isDonor) year else null,
            weightOk = weightOk, area = area!!,
            available = before.available, lastDonationAt = before.lastDonationAt,
            showInDonorSearch = listed, locale = before.locale, termsVersion = before.termsVersion,
        )
        // Back to হ্যাঁ, and nothing else in the way: ask "list me" again (the person's own choice).
        val askListMe = before.weightOk == false && weightOk == true && !listed && block == null && group != null
        state.update { it.copy(saving = true, confirmBloodGroup = false) }
        viewModelScope.launch {
            when (val result = profiles.update(before, draft)) {
                is AppResult.Success -> {
                    // The session re-reads the profile; topics follow a new area or blood group.
                    session.refresh()
                    // The question is its own step: Save isn't "Saving…" behind it.
                    state.update { it.copy(saving = false, saved = !askListMe, askListMe = askListMe) }
                }
                is AppResult.Failure -> state.update { it.copy(saving = false, formError = result.error.messageRes) }
            }
        }
    }

    /**
     * D23 a: the "list me" question after the weight answer went back to হ্যাঁ. Yes lists them (with
     * its consent time); "Not now" leaves them unlisted. Either way Edit profile then closes.
     */
    fun onListMeAnswered(listMe: Boolean) {
        val uid = profile?.uid ?: return
        if (!state.value.askListMe) return
        if (!listMe) {
            state.update { it.copy(askListMe = false, saved = true) }
            return
        }
        state.update { it.copy(askListMe = false, saving = true) }
        viewModelScope.launch {
            // The saved profile, so the new card carries this edit's area and group.
            val saved = (profiles.profile(uid) as? AppResult.Success)?.data
            val result = saved?.let { profiles.setListed(it, true) } ?: AppResult.Failure(AppError.Unknown)
            if (result is AppResult.Success) {
                session.refresh()
                state.update { it.copy(saving = false, saved = true) }
            } else {
                state.update { it.copy(saving = false, formError = (result as AppResult.Failure).error.messageRes) }
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
            // A link was just sent: wait before offering another, as on the confirm-email page.
            if (result is AppResult.Success) startCooldown()
        }
    }

    fun onMessageShown() = state.update { it.copy(message = null, messageArg = null) }
}

/** D34: as the confirm-email page ([VerifyEmailViewModel.COOLDOWN_SECONDS]). */
private const val RESEND_WAIT_SECONDS = VerifyEmailViewModel.COOLDOWN_SECONDS

/** The account-deletion confirmation words (A23). The screen shows DELETE; Bangla names both. */
internal const val DELETE_WORD = "DELETE"
internal const val DELETE_WORD_BN = "মুছুন"
