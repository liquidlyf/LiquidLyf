package io.github.liquidlyf.core.designsystem.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * D25: at a fresh start, the app's copy of the still logo moves into the first screen's own logo.
 * The screen's logo reports where it is (it moves with the language and the text size), and stays
 * hidden while the copy covers it. TalkBack still reads it then: only its drawing is hidden.
 */
@Stable
class StartLogoHandoff {
    /** Where the first screen's stacked logo is, in window pixels (the welcome page, Sign in). */
    var stacked: Rect? by mutableStateOf(null)

    /** Where Home's top-bar logo is, in window pixels. */
    var horizontal: Rect? by mutableStateOf(null)

    /** True until the copy has landed (or faded): the screen's own logo isn't drawn until then. */
    var covering: Boolean by mutableStateOf(true)

    /** The start is over: the copy is gone and the screen's logo is drawn as usual. */
    var done: Boolean by mutableStateOf(false)
}

/** The start's hand-off while it runs; null otherwise (and on every later screen). */
val LocalStartLogoHandoff = compositionLocalOf<StartLogoHandoff?> { null }

/** Reports this logo's place to the start's hand-off, and hides it while the copy covers it. */
internal fun Modifier.startLogoTarget(handoff: StartLogoHandoff?, report: StartLogoHandoff.(Rect) -> Unit): Modifier =
    if (handoff == null) {
        this
    } else {
        this
            .onGloballyPositioned { handoff.report(it.boundsInWindow()) }
            .graphicsLayer { alpha = if (handoff.covering) 0f else 1f }
    }
