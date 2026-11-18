package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where each field of a form is, so a failed "Next" can show the first error (FLOW-04: with an
 * error off-screen, tapping Next seemed to do nothing, and TalkBack didn't move either). Mark each
 * field with [errorAnchor] (around its label, control and error line: bringing it into view scrolls
 * only as far as that); after a failed check call [showFirst]. A text field also gets the keyboard
 * focus, so TalkBack lands on it and reads its error.
 */
@Stable
@OptIn(ExperimentalFoundationApi::class)
class ErrorAnchors<K : Any> internal constructor() {
    private val views = mutableMapOf<K, BringIntoViewRequester>()
    private val focus = mutableMapOf<K, FocusRequester>()

    internal fun viewRequester(key: K): BringIntoViewRequester = views.getOrPut(key) { BringIntoViewRequester() }

    internal fun focusRequester(key: K): FocusRequester = focus.getOrPut(key) { FocusRequester() }

    /**
     * Scrolls the first of [errors] (in screen order, [order]) into view and focuses it if it's a
     * text field. A field that isn't on screen now (another step) is skipped quietly.
     */
    suspend fun showFirst(errors: Set<K>, order: List<K>) {
        val first = order.firstOrNull { it in errors } ?: return
        runCatching { views[first]?.bringIntoView() }
        focus[first]?.let { runCatching { it.requestFocus() } }
    }

    /** Scrolls [key]'s field into view, without focusing it; nothing if it isn't on screen. */
    suspend fun show(key: K) {
        runCatching { views[key]?.bringIntoView() }
    }
}

/**
 * VT-13: the keyboard's Done on the last text field before a box or a choice closes the keyboard,
 * then brings [next] into view once the keyboard has gone. Typing left the 18+ box, "How soon?" and
 * the confirm box hidden under the keyboard.
 */
@Composable
fun <K : Any> keyboardDoneShows(anchors: ErrorAnchors<K>, next: K): KeyboardActions {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    return KeyboardActions(onDone = {
        focus.clearFocus()
        scope.launch {
            withTimeoutOrNull(KEYBOARD_GONE_MS) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
            anchors.show(next)
        }
    })
}

private const val KEYBOARD_GONE_MS = 1_000L

@Composable
fun <K : Any> rememberErrorAnchors(): ErrorAnchors<K> = remember { ErrorAnchors() }

/**
 * After each failed tap ([attempt] counts them), brings the first of [errors] into view, once: a
 * rotation or a theme or language change doesn't scroll there (and open the keyboard) again (D's
 * re-audit).
 */
@Composable
fun <K : Any> ShowFirstErrorOnAttempt(anchors: ErrorAnchors<K>, attempt: Int, errors: Set<K>, order: List<K>) {
    var shown by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        if (attempt > shown) {
            shown = attempt
            anchors.showFirst(errors, order)
        }
    }
}

/** Marks [key]'s field for [anchors]. [textField]: it takes the keyboard focus when it's the first error. */
@OptIn(ExperimentalFoundationApi::class)
fun <K : Any> Modifier.errorAnchor(anchors: ErrorAnchors<K>, key: K, textField: Boolean = false): Modifier {
    val view = bringIntoViewRequester(anchors.viewRequester(key))
    return if (textField) view.focusRequester(anchors.focusRequester(key)) else view
}
