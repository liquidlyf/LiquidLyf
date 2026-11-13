package io.github.liquidlyf.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import io.github.liquidlyf.core.data.settings.ThemeMode

/** The LiquidLyf theme. Dynamic colour is off on purpose (see Color.kt). */
@Composable
fun LiquidLyfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
        LocalExtendedTypography provides LiquidLyfExtendedTypography,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = LiquidLyfTypography,
            shapes = LiquidLyfShapes,
            content = content,
        )
    }
}

/** Tokens Material 3 doesn't cover. Use next to MaterialTheme.colorScheme / typography / shapes. */
object LiquidLyfTheme {
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalExtendedColors.current

    val extendedTypography: ExtendedTypography
        @Composable @ReadOnlyComposable
        get() = LocalExtendedTypography.current

    val spacing: Spacing get() = Spacing
}

/** Whether [ThemeMode] means dark right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
