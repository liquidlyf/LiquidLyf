package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

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
}

@Composable
fun <K : Any> rememberErrorAnchors(): ErrorAnchors<K> = remember { ErrorAnchors() }

/** Marks [key]'s field for [anchors]. [textField]: it takes the keyboard focus when it's the first error. */
@OptIn(ExperimentalFoundationApi::class)
fun <K : Any> Modifier.errorAnchor(anchors: ErrorAnchors<K>, key: K, textField: Boolean = false): Modifier {
    val view = bringIntoViewRequester(anchors.viewRequester(key))
    return if (textField) view.focusRequester(anchors.focusRequester(key)) else view
}
