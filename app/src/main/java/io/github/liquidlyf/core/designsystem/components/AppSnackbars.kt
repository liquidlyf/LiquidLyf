package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A message for the app-wide snackbar, with an optional action. Unlike a screen's own snackbar it
 * outlives the screen that sent it (FLOW-08: "Request hidden · Undo" shows on the screen you go
 * back to). [onAction] must not hold on to a ViewModel: it runs after that screen is gone.
 */
data class AppSnackbar(
    @param:StringRes val message: Int,
    @param:StringRes val actionLabel: Int? = null,
    val onAction: (() -> Unit)? = null,
)

/** The queue the navigation host shows from (AppNavHost). */
object AppSnackbars {
    private val queue = MutableSharedFlow<AppSnackbar>(extraBufferCapacity = 4)
    val messages: SharedFlow<AppSnackbar> = queue.asSharedFlow()

    fun show(snackbar: AppSnackbar) {
        queue.tryEmit(snackbar)
    }
}
