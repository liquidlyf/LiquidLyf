package io.github.liquidlyf.core.util

import androidx.annotation.StringRes
import io.github.liquidlyf.R

/**
 * The result of a repository call. Repositories never throw to the UI and never pass raw
 * Firebase messages up (BUG-028); a failure carries an [AppError] with a translatable message.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** What went wrong, in terms a person can act on. [messageRes] is shown in a snackbar or inline. */
enum class AppError(@param:StringRes val messageRes: Int) {
    Network(R.string.error_network),
    PermissionDenied(R.string.error_permission_denied),
    NotFound(R.string.error_not_found),
    InvalidInput(R.string.error_invalid_input),
    RateLimited(R.string.error_rate_limited),
    /**
     * The Worker's daily alert cap (5+ requests in the 24 h before this one, cancelled ones too):
     * the request is posted and stays in the feed, but nobody was alerted. Not worth a retry.
     */
    DailyAlertLimit(R.string.error_daily_alert_limit),
    Unknown(R.string.error_unknown),

    // Sign-in and account errors (Firebase Auth error codes, mapped in ErrorMapper).
    /** Wrong email or password. Deliberately doesn't say which, or whether the account exists. */
    InvalidCredentials(R.string.error_invalid_credentials),
    EmailInUse(R.string.error_email_in_use),
    WeakPassword(R.string.error_weak_password),
    InvalidEmail(R.string.error_invalid_email),
    AccountDisabled(R.string.error_account_disabled),
    /** Sensitive changes (password, deletion) need a fresh sign-in first. */
    RecentLoginRequired(R.string.error_recent_login_required),
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data
