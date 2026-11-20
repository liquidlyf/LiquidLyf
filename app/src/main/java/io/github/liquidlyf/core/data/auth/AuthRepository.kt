package io.github.liquidlyf.core.data.auth

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import io.github.liquidlyf.core.firebase.CrashReports
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The signed-in Firebase user, as much as the app needs. */
/**
 * Custom claims from the ID token, set by the maintainer's admin scripts (phase-07 7.4): `admin`
 * opens Moderation, `banned` suspends the account. [uid] says whose they are, so a stale value
 * never applies to the next person signed in.
 */
data class Claims(val uid: String? = null, val admin: Boolean = false, val banned: Boolean = false)

data class AuthUser(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    /** "password", "google.com"… Change password is hidden for Google-only accounts. */
    val providers: Set<String>,
) {
    val hasPassword: Boolean get() = EmailAuthProvider.PROVIDER_ID in providers
}

/** D32: what a fresh look at the account found. */
sealed interface AccountCheck {
    /** Nothing new, or no answer (offline): the next check tries again. */
    data object Same : AccountCheck

    /** The email changed (the link was opened, on any device); the app now shows it. */
    data class EmailChanged(val email: String) : AccountCheck

    /**
     * Firebase ended this phone's session (it does after an email or password change), so it's
     * signed out. [newEmail]: the account's new address, when the email is what changed.
     */
    data class SessionEnded(val newEmail: String?) : AccountCheck
}

/**
 * Firebase Auth (doc 06 §4.1). Every email Firebase sends (verify, reset, change email) is in the
 * app's language: useAppLanguage() before each send, so Bangla users get Bangla mails.
 * [authState] is the only source of truth for "signed in"; there is
 * no "is_logged_in" preference (BUG-016). Errors come back as [AppError], never raw Firebase text
 * (BUG-028). No phone auth: the free Spark plan has no SMS.
 */
interface AuthRepository {
    /** Null when signed out. Also updates after [reloadUser], when "email verified" can change. */
    val authState: StateFlow<AuthUser?>

    /** Who Firebase's emails come from (the project's default sender), for the "check Spam" hints. */
    val emailSender: String

    /** Read again on every ID token change: sign-in, and each hourly refresh (a new claim shows within the hour). */
    val claims: StateFlow<Claims>

    suspend fun signInEmail(email: String, password: String): AppResult<AuthUser>

    /** Creates the account, sets the display name and sends the verification email. */
    suspend fun signUpEmail(name: String, email: String, password: String): AppResult<AuthUser>

    suspend fun signInWithGoogle(idToken: String): AppResult<AuthUser>

    suspend fun sendVerification(): AppResult<Unit>

    /** Re-reads the user and refreshes the ID token, so Firestore sees a new `email_verified`. */
    suspend fun reloadUser(): AppResult<AuthUser?>

    /** Always reports success for a well-formed email, so nobody can probe which emails exist. */
    suspend fun sendPasswordReset(email: String): AppResult<Unit>

    suspend fun reauthenticate(password: String): AppResult<Unit>

    suspend fun changePassword(newPassword: String): AppResult<Unit>

    /** P3: a Google-only account confirms who they are with a fresh Google ID token. */
    suspend fun reauthenticateWithGoogle(idToken: String): AppResult<Unit>

    /**
     * P3: links an email password to the same account (needs a recent sign-in), so the user can
     * sign in with Google or with their email and this password.
     */
    suspend fun addPassword(password: String): AppResult<AuthUser>

    /** Sends a link to [newEmail]; the email changes only when the user opens it. */
    suspend fun changeEmail(newEmail: String): AppResult<Unit>

    /** D34: the address the last "Change email" on this phone asked for, until the change shows (kept on the phone). */
    val requestedEmail: StateFlow<RequestedEmail?>

    /** Sign-out: the asked-for address goes too. */
    suspend fun forgetRequestedEmail()

    /**
     * D32: reads the account again and renews this phone's session. Without it the phone shows the
     * email it saved until the next sign-in, and a session Firebase ended (after an email change)
     * only signs out at the hourly renewal, with no reason given.
     */
    suspend fun checkAccount(): AccountCheck

    fun signOut()

    /** The last step of account deletion, after the user's documents are gone. */
    suspend fun deleteUser(): AppResult<Unit>
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val requested: RequestedEmailStore,
    private val clock: Clock,
) : AuthRepository {

    private val state = MutableStateFlow(auth.currentUser?.toAuthUser())
    override val authState: StateFlow<AuthUser?> = state.asStateFlow()
    override val emailSender: String = "noreply@${auth.app.options.projectId}.firebaseapp.com"

    private val claimsState = MutableStateFlow(Claims())
    override val claims: StateFlow<Claims> = claimsState.asStateFlow()

    init {
        // A singleton for the whole process: the listeners live as long as the app does.
        auth.addAuthStateListener { state.value = it.currentUser?.toAuthUser() }
        auth.addIdTokenListener(FirebaseAuth.IdTokenListener { readClaims(it.currentUser) })
    }

    private fun readClaims(user: FirebaseUser?) {
        if (user == null) {
            claimsState.value = Claims()
            return
        }
        user.getIdToken(false).addOnSuccessListener { token ->
            val c = token.claims
            claimsState.value = Claims(user.uid, admin = c["admin"] == true, banned = c["banned"] == true)
        }
    }

    override suspend fun signInEmail(email: String, password: String): AppResult<AuthUser> = appResultOf {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        publish()!!
    }

    override suspend fun signUpEmail(name: String, email: String, password: String): AppResult<AuthUser> = appResultOf {
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user!!
        // Creating the account signs the user in, and the auth gate at once replaces the sign-up
        // screen with Verify email, cancelling the screen's scope. The name and the verification
        // email must still go out (A15: without this, the first email was never sent).
        withContext(NonCancellable) {
            user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
            auth.useAppLanguage()
            user.sendEmailVerification().await()
        }
        publish()!!
    }

    override suspend fun signInWithGoogle(idToken: String): AppResult<AuthUser> = appResultOf {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        publish()!!
    }

    override suspend fun sendVerification(): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        return appResultOf {
            auth.useAppLanguage()
            user.sendEmailVerification().await()
        }
    }

    override suspend fun reloadUser(): AppResult<AuthUser?> {
        val user = auth.currentUser ?: return AppResult.Success(null)
        return appResultOf {
            user.reload().await()
            // A fresh token carries the new email_verified claim to Firestore's rules.
            user.getIdToken(true).await()
            publish()
        }
    }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> {
        val result: AppResult<Unit> = appResultOf {
            auth.useAppLanguage()
            auth.sendPasswordResetEmail(email.trim()).await()
            Unit
        }
        // "No such user" must look exactly like success.
        return if (result is AppResult.Failure && result.error == AppError.InvalidCredentials) AppResult.Success(Unit) else result
    }

    override suspend fun reauthenticate(password: String): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        val email = user.email ?: return AppResult.Failure(AppError.InvalidCredentials)
        return appResultOf { user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await() }
    }

    override suspend fun changePassword(newPassword: String): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        return appResultOf { user.updatePassword(newPassword).await() }
    }

    override suspend fun reauthenticateWithGoogle(idToken: String): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        return appResultOf { user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await() }
    }

    override suspend fun addPassword(password: String): AppResult<AuthUser> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        val email = user.email ?: return AppResult.Failure(AppError.InvalidEmail)
        return try {
            try {
                user.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await()
            } catch (e: FirebaseAuthException) {
                when (e.errorCode) {
                    // Already linked (say, from another phone): the account has a password, the goal.
                    "ERROR_PROVIDER_ALREADY_LINKED" -> Unit
                    // With one account per email, "in use" can only mean this same account. The Auth
                    // emulator answers so; setting the password directly adds the provider instead.
                    "ERROR_EMAIL_ALREADY_IN_USE" -> user.updatePassword(password).await()
                    else -> throw e
                }
            }
            user.reload().await()
            AppResult.Success(publish()!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(CrashReports.appError(e))
        }
    }

    override suspend fun changeEmail(newEmail: String): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        return appResultOf {
            auth.useAppLanguage()
            user.verifyBeforeUpdateEmail(newEmail.trim()).await()
            requested.keep(newEmail.trim())
        }
    }

    override val requestedEmail: StateFlow<RequestedEmail?> get() = requested.requested

    override suspend fun forgetRequestedEmail() = requested.clear()

    override suspend fun checkAccount(): AccountCheck {
        val user = auth.currentUser ?: return AccountCheck.Same
        val before = user.email
        try {
            user.reload().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: FirebaseAuthInvalidUserException) {
            return ended(newEmail = null) // gone, disabled, or its session ended
        } catch (_: Exception) {
            return AccountCheck.Same // offline: try again next time
        }
        val now = auth.currentUser?.email ?: before
        try {
            user.getIdToken(true).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: FirebaseAuthInvalidUserException) {
            return ended(newEmail = now.takeIf { it != before })
        } catch (_: Exception) {
            // Offline for the token: still show what the account says now.
        }
        publish()
        if (now != null && now != before) {
            requested.clear()
            return AccountCheck.EmailChanged(now)
        }
        return AccountCheck.Same
    }

    /** Only what the app knows: the new address from the account, or the one asked for here (3 days). */
    private suspend fun ended(newEmail: String?): AccountCheck {
        val email = newEmail ?: requested.requested.value?.takeIf { it.freshAt(clock.now()) }?.email
        requested.clear()
        auth.signOut()
        state.value = null
        return AccountCheck.SessionEnded(email)
    }

    override fun signOut() {
        auth.signOut()
        state.value = null
    }

    override suspend fun deleteUser(): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Success(Unit)
        return appResultOf {
            user.delete().await()
            state.value = null
        }
    }

    /** The listener doesn't fire for reload() or a profile change, so publish by hand. */
    private fun publish(): AuthUser? = auth.currentUser?.toAuthUser().also { state.value = it }

    private fun FirebaseUser.toAuthUser() = AuthUser(
        uid = uid,
        email = email,
        emailVerified = isEmailVerified,
        displayName = displayName,
        providers = providerData.map { it.providerId }.toSet(),
    )
}
