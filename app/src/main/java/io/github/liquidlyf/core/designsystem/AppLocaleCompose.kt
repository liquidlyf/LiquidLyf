package io.github.liquidlyf.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.PlaceName

/** The app's language right now (the per-app locale, which the configuration carries). */
@Composable
fun currentAppLocale(): AppLocale =
    if (LocalConfiguration.current.locales[0].language == "bn") AppLocale.BN else AppLocale.EN

/** A place name in the app's language, falling back to English when no Bangla name exists. */
@Composable
fun PlaceName.localized(): String = localized(currentAppLocale())
