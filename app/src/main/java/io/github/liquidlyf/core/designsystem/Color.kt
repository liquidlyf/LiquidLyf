package io.github.liquidlyf.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Colour tokens (doc 04 §3, seed #A4161A). The only file with colour values: UI code uses
 * MaterialTheme.colorScheme and LiquidLyfTheme.extendedColors, never hex.
 * Values not listed in the audit table follow the Material 3 tonal palettes of the same
 * hues; every text pair meets WCAG AA (4.5:1).
 * Dynamic colour is off on purpose: the brand red is how people recognise the app.
 *
 * "Calm white, one red" (UI/UX review, D4 a, 17 Nov 2026):
 * - red (primary) is for three things only: the main action, Critical urgency (error) and the
 *   brand (logo, blood-group ring). A choice the person made (a selected chip) may be red too;
 * - cards are white (surfaceContainerLowest; dark: surfaceContainerLow) with an outlineVariant
 *   border, on the warm off-white background;
 * - secondaryContainer is a warm grey (UI-02), so tonal buttons and notices are neutral. The pink
 *   primaryContainer is left for the navigation indicator;
 * - status colours follow one map (COLOR-01, StatusTone): Critical red, Urgent amber, done green,
 *   everything else neutral. tertiary (cyan) is kept for Material's own parts, not for status.
 */

internal val LightColors = lightColorScheme(
    primary = Color(0xFFA4161A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410003),
    inversePrimary = Color(0xFFFFB4AB),
    secondary = Color(0xFF775653),
    onSecondary = Color(0xFFFFFFFF),
    // Warm grey, not the primary pink (UI-02): 13.68:1 for its text.
    secondaryContainer = Color(0xFFEFE3E1),
    onSecondaryContainer = Color(0xFF2C1513),
    tertiary = Color(0xFF00696F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF0F7),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceTint = Color(0xFFA4161A),
    inverseSurface = Color(0xFF392E2D),
    inverseOnSurface = Color(0xFFFFEDEB),
    outline = Color(0xFF857371),
    outlineVariant = Color(0xFFD8C2BF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFE8D6D4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EF),
    surfaceContainer = Color(0xFFFBEAE8),
    surfaceContainerHigh = Color(0xFFF5E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF8C0E13),
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = Color(0xFFA4161A),
    secondary = Color(0xFFE7BDB8),
    onSecondary = Color(0xFF442927),
    // Warm grey (UI-02): 7.83:1 for its text.
    secondaryContainer = Color(0xFF4A3F3E),
    onSecondaryContainer = Color(0xFFF1DEDC),
    tertiary = Color(0xFF80D4DA),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF0F7),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF1A1111),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1111),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    surfaceTint = Color(0xFFFFB4AB),
    inverseSurface = Color(0xFFF1DEDC),
    inverseOnSurface = Color(0xFF392E2D),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423736),
    surfaceDim = Color(0xFF1A1111),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322827),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/** Roles Material 3 doesn't have: success (donated, fulfilled) and warning (urgent). */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightExtendedColors = ExtendedColors(
    success = Color(0xFF1E6B35),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB7F1BE),
    onSuccessContainer = Color(0xFF00210B),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
)

internal val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF9CD5A3),
    onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF005322),
    onSuccessContainer = Color(0xFFB7F1BE),
    warning = Color(0xFFF5BF48),
    onWarning = Color(0xFF402D00),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDF9E),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** System-bar scrims for 3-button navigation, as ARGB ints for enableEdgeToEdge(). */
object SystemBarScrims {
    const val LIGHT: Int = 0xE6FFF8F7.toInt()
    const val DARK: Int = 0x801A1111.toInt()
}
