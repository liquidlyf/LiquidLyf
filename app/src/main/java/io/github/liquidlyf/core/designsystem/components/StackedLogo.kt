package io.github.liquidlyf.core.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R

/**
 * The stacked LiquidLyf logo (branding/logo-full.svg, dark: logo-full-dark.svg) for the welcome page
 * and Sign in (BRAND-01, N4). The horizontal logo is for Home's top bar only. With the keyboard open
 * it shrinks, and with large text it's smaller, so it never pushes the fields off the screen.
 * Dark or light follows the app's own theme setting, which `drawable-night` wouldn't. At a fresh
 * start, the start logo moves into it (D25, [StartLogoHandoff]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StackedLogo(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val target = when {
        WindowInsets.isImeVisible -> 92.dp
        isLargeText() -> 104.dp
        else -> 150.dp
    }
    val height by animateDpAsState(target, label = "logoHeight")
    Image(
        painterResource(if (dark) R.drawable.logo_full_dark else R.drawable.logo_full),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier
            .height(height)
            .aspectRatio(LOGO_RATIO)
            .startLogoTarget(LocalStartLogoHandoff.current) { stacked = it },
    )
}

/** branding/logo-full.svg's viewBox, 1087.41 × 782.3. */
private const val LOGO_RATIO = 1087.41f / 782.3f

/**
 * The horizontal logo (branding/logo-horizontal.svg, dark: logo-horizontal-dark.svg), for Home's
 * top bar only (BRAND-01, N6). Its words are the app's name, so TalkBack reads that. At a fresh
 * start on Home, the start logo shrinks into it (D25).
 */
@Composable
fun HorizontalLogo(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Image(
        painterResource(if (dark) R.drawable.logo_horizontal_dark else R.drawable.logo_horizontal),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier
            .height(28.dp)
            .aspectRatio(HORIZONTAL_RATIO)
            .startLogoTarget(LocalStartLogoHandoff.current) { horizontal = it },
    )
}

/** branding/logo-horizontal.svg's viewBox, 1417.6 × 300.14. */
private const val HORIZONTAL_RATIO = 1417.6f / 300.14f
