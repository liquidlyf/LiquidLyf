package io.github.liquidlyf.core.designsystem.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app-wide snackbar host, shown above the bottom navigation. Feedback uses snackbars
 * and inline errors, never Toasts (doc 04 §3).
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState provided. Wrap the content in AppNavHost or provide one in a preview.")
}
