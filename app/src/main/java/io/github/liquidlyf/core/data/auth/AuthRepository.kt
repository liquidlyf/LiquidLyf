package io.github.liquidlyf.core.data.auth

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** The signed-in Firebase user, as much as the app needs. */
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

    /** Sends a link to [newEmail]; the email changes only when the user opens it. */
    suspend fun changeEmail(newEmail: String): AppResult<Unit>

    fun signOut()

    /** The last step of account deletion, after the user's documents are gone. */
    suspend fun deleteUser(): AppResult<Unit>
}

@Singleton
class FirebaseAuthRepository @Inject constructor(private val auth: FirebaseAuth) : AuthRepository {

    private val state = MutableStateFlow(auth.currentUser?.toAuthUser())
    override val authState: StateFlow<AuthUser?> = state.asStateFlow()

    init {
        // A singleton for the whole process: the listener lives as long as the app does.
        auth.addAuthStateListener { state.value = it.currentUser?.toAuthUser() }
    }

    override suspend fun signInEmail(email: String, password: String): AppResult<AuthUser> = appResultOf {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        publish()!!
    }

    override suspend fun signUpEmail(name: String, email: String, password: String): AppResult<AuthUser> = appResultOf {
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user!!
        user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        auth.useAppLanguage()
        user.sendEmailVerification().await()
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

    override suspend fun changeEmail(newEmail: String): AppResult<Unit> {
        val user = auth.currentUser ?: return AppResult.Failure(AppError.PermissionDenied)
        return appResultOf {
            auth.useAppLanguage()
            user.verifyBeforeUpdateEmail(newEmail.trim()).await()
            Unit
        }
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
