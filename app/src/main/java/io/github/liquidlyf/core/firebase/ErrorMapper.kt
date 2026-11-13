package io.github.liquidlyf.core.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Maps a Firebase or I/O failure to an [AppError]. Raw exception text never reaches the
 * UI (BUG-028). Phase 3 adds the Firebase Auth error codes.
 */
fun Throwable.toAppError(): AppError = when (this) {
    is FirebaseNetworkException, is IOException -> AppError.Network
    is FirebaseTooManyRequestsException -> AppError.RateLimited
    is FirebaseAuthException -> authError(errorCode)
    is FirebaseFirestoreException -> when (code) {
        Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED -> AppError.Network
        Code.PERMISSION_DENIED, Code.UNAUTHENTICATED -> AppError.PermissionDenied
        Code.NOT_FOUND -> AppError.NotFound
        Code.INVALID_ARGUMENT, Code.OUT_OF_RANGE -> AppError.InvalidInput
        Code.RESOURCE_EXHAUSTED -> AppError.RateLimited
        else -> AppError.Unknown
    }
    else -> AppError.Unknown
}

/**
 * Runs [block] and wraps its outcome in an [AppResult]. Coroutine cancellation is rethrown,
 * never reported as an error.
 */
suspend inline fun <T> appResultOf(crossinline block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(e.toAppError())
}

/**
 * Firebase Auth error codes. A wrong password, an unknown email and a bad credential all read the
 * same ("wrong email or password"), so the app never reveals which emails have accounts.
 */
internal fun authError(code: String): AppError = when (code) {
    "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL",
    "ERROR_INVALID_USER_TOKEN", "ERROR_USER_MISMATCH",
    -> AppError.InvalidCredentials
    "ERROR_INVALID_EMAIL" -> AppError.InvalidEmail
    "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL",
    -> AppError.EmailInUse
    "ERROR_WEAK_PASSWORD" -> AppError.WeakPassword
    "ERROR_USER_DISABLED" -> AppError.AccountDisabled
    "ERROR_REQUIRES_RECENT_LOGIN", "ERROR_USER_TOKEN_EXPIRED" -> AppError.RecentLoginRequired
    "ERROR_TOO_MANY_REQUESTS" -> AppError.RateLimited
    "ERROR_NETWORK_REQUEST_FAILED" -> AppError.Network
    else -> AppError.Unknown
}
