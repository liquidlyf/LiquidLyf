package io.github.liquidlyf.feature.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * Sign-in screens. None of them navigates on success: the session changes and the auth gate
 * (navigation/AuthGate.kt) moves the user, so a screen can never leave someone stranded.
 * Errors are string resources shown inline under the field they belong to (BUG-028).
 */

@StringRes
private fun emailError(email: String): Int? = when {
    email.isBlank() -> R.string.error_email_required
    !Validators.isValidEmail(email) -> R.string.error_invalid_email
    else -> null
}

/**
 * What a Google sheet result means for a form: still signing in (null error, keep loading),
 * cancelled (stop, say nothing) or a problem to show. Used by Sign in and Create account.
 */
private sealed interface GoogleOutcome {
    data object SignedIn : GoogleOutcome

    data object Cancelled : GoogleOutcome

    data class Problem(@StringRes val message: Int) : GoogleOutcome
}

private suspend fun AuthRepository.finishGoogle(result: GoogleSignInResult): GoogleOutcome = when (result) {
    is GoogleSignInResult.Token -> when (val signIn = signInWithGoogle(result.idToken)) {
        is AppResult.Success -> GoogleOutcome.SignedIn // the gate moves on
        is AppResult.Failure -> GoogleOutcome.Problem(signIn.error.messageRes)
    }
    GoogleSignInResult.Cancelled -> GoogleOutcome.Cancelled
    GoogleSignInResult.NoAccount -> GoogleOutcome.Problem(R.string.error_google_no_account)
    GoogleSignInResult.Failed -> GoogleOutcome.Problem(R.string.error_google_failed)
}

// ---- Sign in ----

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val formError: Int? = null,
    /** True while signing in, and after success until the gate moves on (no double submit). */
    val loading: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = state.asStateFlow()

    fun onEmailChange(value: String) = state.update { it.copy(email = value, emailError = null, formError = null) }

    fun onPasswordChange(value: String) = state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun signIn() {
        val s = state.value
        if (s.loading) return
        val emailError = emailError(s.email)
        val passwordError = if (s.password.isEmpty()) R.string.error_password_required else null
        if (emailError != null || passwordError != null) {
            state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            when (val result = auth.signInEmail(s.email, s.password)) {
                is AppResult.Success -> Unit // the gate moves on; Autofill offers to save the password
                is AppResult.Failure -> state.update {
                    when (result.error) {
                        AppError.InvalidEmail -> it.copy(loading = false, emailError = result.error.messageRes)
                        AppError.InvalidCredentials -> it.copy(loading = false, passwordError = result.error.messageRes)
                        else -> it.copy(loading = false, formError = result.error.messageRes)
                    }
                }
            }
        }
    }

    /** "Continue with Google" was tapped; the screen shows Google's sheet. False if busy. */
    fun onGoogleStarted(): Boolean {
        if (state.value.loading) return false
        state.update { it.copy(loading = true, formError = null) }
        return true
    }

    /** What Google's sheet returned. A token signs in to Firebase; the gate then moves on. */
    fun onGoogleResult(result: GoogleSignInResult) {
        viewModelScope.launch {
            when (val outcome = auth.finishGoogle(result)) {
                GoogleOutcome.SignedIn -> Unit
                GoogleOutcome.Cancelled -> state.update { it.copy(loading = false) }
                is GoogleOutcome.Problem -> state.update { it.copy(loading = false, formError = outcome.message) }
            }
        }
    }
}

// ---- Sign up ----

data class SignUpUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    /** 18+ and Terms: required, and never ticked for the user (doc 09 Phase 3.5). */
    val consent: Boolean = false,
    @StringRes val nameError: Int? = null,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val consentError: Int? = null,
    @StringRes val formError: Int? = null,
    val loading: Boolean = false,
    /** Counts failed taps on Create account: the screen brings the first error into view (FLOW-04). */
    val errorAttempt: Int = 0,
)

@HiltViewModel
class SignUpViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(SignUpUiState())
    val uiState: StateFlow<SignUpUiState> = state.asStateFlow()

    fun onNameChange(value: String) = state.update { it.copy(name = value, nameError = null, formError = null) }

    fun onEmailChange(value: String) = state.update { it.copy(email = value, emailError = null, formError = null) }

    fun onPasswordChange(value: String) = state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun onConsentChange(value: Boolean) = state.update { it.copy(consent = value, consentError = null) }

    fun signUp() {
        val s = state.value
        if (s.loading) return
        val errors = s.copy(
            nameError = if (Validators.isValidName(s.name)) null else R.string.error_name_invalid,
            emailError = emailError(s.email),
            passwordError = if (s.password.length >= Validators.PASSWORD_MIN) null else R.string.error_password_short,
            consentError = if (s.consent) null else R.string.error_consent_required,
        )
        if (listOf(errors.nameError, errors.emailError, errors.passwordError, errors.consentError).any { it != null }) {
            state.value = errors.copy(errorAttempt = s.errorAttempt + 1)
            return
        }
        state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            when (val result = auth.signUpEmail(Validators.normalizeName(s.name), s.email, s.password)) {
                is AppResult.Success -> Unit // the gate moves on; Autofill offers to save the password
                is AppResult.Failure -> state.update {
                    when (result.error) {
                        AppError.EmailInUse, AppError.InvalidEmail -> it.copy(loading = false, emailError = result.error.messageRes)
                        AppError.WeakPassword -> it.copy(loading = false, passwordError = result.error.messageRes)
                        else -> it.copy(loading = false, formError = result.error.messageRes)
                    }
                }
            }
        }
    }

    /** "Continue with Google" on Create account (P2): a new Google account skips this form. */
    fun onGoogleStarted(): Boolean {
        if (state.value.loading) return false
        state.update { it.copy(loading = true, formError = null) }
        return true
    }

    /** New Google accounts confirm 18+ and the Terms in profile setup instead. */
    fun onGoogleResult(result: GoogleSignInResult) {
        viewModelScope.launch {
            when (val outcome = auth.finishGoogle(result)) {
                GoogleOutcome.SignedIn -> Unit
                GoogleOutcome.Cancelled -> state.update { it.copy(loading = false) }
                is GoogleOutcome.Problem -> state.update { it.copy(loading = false, formError = outcome.message) }
            }
        }
    }
}

// ---- Verify email ----

data class VerifyEmailUiState(
    val email: String = "",
    /** Firebase's sender address, for the "check Spam" hint. */
    val sender: String = "",
    /** Seconds until Resend works again (BUG-026: no resend spam). */
    val resendIn: Int = VerifyEmailViewModel.COOLDOWN_SECONDS,
    val checking: Boolean = false,
    @StringRes val message: Int? = null,
)

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val state = MutableStateFlow(VerifyEmailUiState(email = auth.authState.value?.email.orEmpty(), sender = auth.emailSender))
    val uiState: StateFlow<VerifyEmailUiState> = state.asStateFlow()
    private var countdown: Job? = null

    init {
        // An email was just sent (sign-up) or is on its way: wait before offering another.
        startCooldown()
    }

    fun resend() {
        if (state.value.resendIn > 0) return
        startCooldown()
        viewModelScope.launch {
            val result = auth.sendVerification()
            state.update {
                it.copy(message = if (result is AppResult.Failure) result.error.messageRes else R.string.verify_email_sent)
            }
        }
    }

    /** "I've verified": re-read the user; the gate moves on if the email is now verified. */
    fun checkVerified() {
        if (state.value.checking) return
        state.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            when (val result = auth.reloadUser()) {
                is AppResult.Success ->
                    if (result.data?.emailVerified == true) {
                        session.refresh()
                    } else {
                        state.update { it.copy(checking = false, message = R.string.verify_email_not_yet) }
                    }
                is AppResult.Failure -> state.update { it.copy(checking = false, message = result.error.messageRes) }
            }
        }
    }

    /**
     * FLOW-09: back from the email app, check by itself. Silent: it moves on if verified and says
     * nothing otherwise ("I've verified" and Resend stay, if it ever gets stuck).
     */
    fun checkQuietly() {
        if (state.value.checking) return
        viewModelScope.launch {
            val result = auth.reloadUser()
            if (result is AppResult.Success && result.data?.emailVerified == true) session.refresh()
        }
    }

    fun onMessageShown() = state.update { it.copy(message = null) }

    /** Signs out, so the user can sign up again with another address. */
    fun useDifferentEmail() {
        viewModelScope.launch { session.signOut() }
    }

    private fun startCooldown() {
        countdown?.cancel()
        state.update { it.copy(resendIn = COOLDOWN_SECONDS) }
        countdown = viewModelScope.launch {
            while (state.value.resendIn > 0) {
                delay(1_000)
                state.update { it.copy(resendIn = it.resendIn - 1) }
            }
        }
    }

    companion object {
        const val COOLDOWN_SECONDS = 60
    }
}

// ---- Forgot password ----

data class ForgotPasswordUiState(
    val email: String = "",
    @StringRes val emailError: Int? = null,
    @StringRes val formError: Int? = null,
    val loading: Boolean = false,
    /** Set once the link is sent: the screen shows "check your email". */
    val sentTo: String? = null,
    /** Firebase's sender address, for the "check Spam" hint. */
    val sender: String = "",
)

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(ForgotPasswordUiState(sender = auth.emailSender))
    val uiState: StateFlow<ForgotPasswordUiState> = state.asStateFlow()

    fun onEmailChange(value: String) = state.update { it.copy(email = value, emailError = null, formError = null) }

    /** Low 25: back to the form, with the address as it was, to correct it. */
    fun useDifferentEmail() = state.update { it.copy(sentTo = null) }

    fun send() {
        val s = state.value
        if (s.loading) return
        emailError(s.email)?.let { error ->
            state.update { it.copy(emailError = error) }
            return
        }
        state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            val result = auth.sendPasswordReset(s.email)
            state.update {
                if (result is AppResult.Failure) {
                    it.copy(loading = false, formError = result.error.messageRes)
                } else {
                    it.copy(loading = false, sentTo = s.email.trim())
                }
            }
        }
    }
}
