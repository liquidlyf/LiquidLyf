package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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

/**
 * The queue the navigation host shows from (AppNavHost). An entry stays until it has been shown to
 * its end, dismissed or used ([done]): a rotation, theme or language change mid-show recreates the
 * screen, which then shows it again instead of losing "Request hidden · Undo" (D's re-audit).
 */
object AppSnackbars {
    /** One queued message; its own identity, so two equal messages both show. */
    class Entry internal constructor(val snackbar: AppSnackbar)

    private val queue = MutableStateFlow<List<Entry>>(emptyList())

    /** The one to show now, or null. */
    val current: Flow<Entry?> = queue.map { it.firstOrNull() }.distinctUntilChanged()

    fun show(snackbar: AppSnackbar) {
        queue.update { (it + Entry(snackbar)).takeLast(MAX_QUEUED) }
    }

    /** Shown to its end, dismissed or used: the next one follows. */
    fun done(entry: Entry) {
        queue.update { list -> list.filterNot { it === entry } }
    }

    /** Tests only: an empty queue (this object outlives a test). */
    internal fun clear() {
        queue.value = emptyList()
    }

    private const val MAX_QUEUED = 4
}
