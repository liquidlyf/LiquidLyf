package io.github.liquidlyf.core.data.auth

import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.components.AppSnackbar
import io.github.liquidlyf.core.designsystem.components.AppSnackbars
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** D32: what Sign in says after Firebase ended the session (never after the person's own Sign out). */
sealed interface SignInNotice {
    /** "Your email was changed. Sign in with {email}.", with it filled in. */
    data class EmailChanged(val email: String) : SignInNotice

    /** The app doesn't know why (a new password elsewhere, a disabled account): "You were signed out." */
    data object SignedOut : SignInNotice
}

/**
 * D32 (A49's tests on the live project): after a change of email the app kept showing the old one,
 * and Firebase's ended session would only have signed the phone out at the hourly renewal, with no
 * reason given. So the app looks at the account again when it starts and each time it comes back,
 * at most every 10 minutes, and right after "Change email" was asked here at every return (people
 * come back from the email within minutes). A network failure changes nothing; only Firebase's own
 * "session ended" answers sign out, and Sign in then says what the app knows.
 */
@Singleton
class AccountChecker @Inject constructor(
    private val auth: AuthRepository,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private var lastAt: Instant? = null

    private val notice = MutableStateFlow<SignInNotice?>(null)
    val signInNotice: StateFlow<SignInNotice?> = notice.asStateFlow()

    init {
        // Signed in again: the notice has done its job.
        scope.launch { auth.authState.filterNotNull().collect { notice.value = null } }
    }

    /** App start, and each return to it. */
    fun onAppVisible() {
        if (auth.authState.value == null) return
        val now = clock.now()
        val last = lastAt
        // After "Change email" here: every return, until the change shows.
        val waiting = auth.requestedEmail.value?.freshAt(now) == true
        if (!waiting && last != null && now.isBefore(last.plus(EVERY))) return
        lastAt = now
        scope.launch { check() }
    }

    internal suspend fun check() {
        when (val found = auth.checkAccount()) {
            // D34: "Your email is now {email}.", over whichever screen is open.
            is AccountCheck.EmailChanged -> AppSnackbars.show(AppSnackbar(R.string.email_changed_done, args = listOf(found.email)))
            is AccountCheck.SessionEnded -> notice.value = found.newEmail?.let(SignInNotice::EmailChanged) ?: SignInNotice.SignedOut
            AccountCheck.Same -> Unit
        }
    }

    companion object {
        val EVERY: Duration = Duration.ofMinutes(10)
    }
}
