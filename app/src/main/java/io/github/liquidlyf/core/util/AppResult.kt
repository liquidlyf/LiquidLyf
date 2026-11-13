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
    Unknown(R.string.error_unknown),
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
