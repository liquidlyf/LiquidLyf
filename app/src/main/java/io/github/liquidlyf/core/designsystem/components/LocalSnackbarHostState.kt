package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/**
 * The app-wide snackbar host, shown above the bottom navigation. Feedback uses snackbars
 * and inline errors, never Toasts (doc 04 §3).
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState provided. Wrap the content in AppNavHost or provide one in a preview.")
}

/**
 * VT-16: how high snackbars must sit. Every bar pinned at the bottom (the tabs, a screen's own
 * bottom bar, the floating Request blood) reports how far its top is from the window's bottom, and
 * snackbars sit above the highest. The one host only knew the tabs, so a snackbar covered the
 * requester's Call button and the red Request blood.
 */
@Stable
class SnackbarClearance {
    private val fromBottom = mutableStateMapOf<Any, Int>()

    /** Pixels from the window's bottom to the top of the highest bar on screen. */
    val px: Int get() = fromBottom.values.maxOrNull() ?: 0

    internal fun set(key: Any, px: Int) {
        if (fromBottom[key] != px) fromBottom[key] = px
    }

    internal fun remove(key: Any) {
        fromBottom.remove(key)
    }
}

val LocalSnackbarClearance = staticCompositionLocalOf { SnackbarClearance() }

/** Marks a bar pinned at the bottom of the screen: snackbars stay above it (VT-16). */
fun Modifier.keepsSnackbarsAbove(): Modifier = composed {
    val clearance = LocalSnackbarClearance.current
    val view = LocalView.current
    val key = remember { Any() }
    DisposableEffect(clearance, key) { onDispose { clearance.remove(key) } }
    onGloballyPositioned { c ->
        clearance.set(key, (view.rootView.height - c.positionInWindow().y).roundToInt().coerceAtLeast(0))
    }
}

/** The app's snackbars, above every bar marked with [keepsSnackbarsAbove] (or the system bar). */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bottom = maxOf(LocalSnackbarClearance.current.px, WindowInsets.navigationBars.getBottom(density))
    SnackbarHost(state, modifier.padding(bottom = with(density) { bottom.toDp() })) { LLSnackbar(it) }
}
