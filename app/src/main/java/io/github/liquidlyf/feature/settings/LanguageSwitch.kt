package io.github.liquidlyf.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.AppLanguageStore
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.chosenSegmentColors
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.model.AppLocale
import javax.inject.Inject

/**
 * The language choice before sign-in (the maintainer's bug, 16 Nov): Settings → Language opens
 * only after sign-in, and Android 11–12 have no per-app language menu. It uses the same
 * [AppLanguageStore] as Settings, so both always show the same choice.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(private val store: AppLanguageStore) : ViewModel() {
    /** The screens restart in [choice]. */
    fun set(choice: AppLanguage) = store.set(choice)
}

/** The language on screen now: the chosen one, or the phone's while the choice is "Phone's language". */
@Composable
internal fun shownLanguage(): AppLanguage =
    if (currentAppLocale() == AppLocale.BN) AppLanguage.BANGLA else AppLanguage.ENGLISH

/**
 * A language's own name, never translated (a Bangla reader looks for "বাংলা" on an English
 * screen), tagged with its language so TalkBack reads it with the right voice.
 */
@Composable
private fun nameOf(language: AppLanguage): AnnotatedString {
    val name = stringResource(if (language == AppLanguage.BANGLA) R.string.language_bangla else R.string.language_english)
    return buildAnnotatedString { withStyle(SpanStyle(localeList = LocaleList(language.tag))) { append(name) } }
}

private val choices = listOf(AppLanguage.ENGLISH, AppLanguage.BANGLA)

/**
 * "English | বাংলা" on the onboarding pages. Picking the language already shown changes
 * nothing, so a new user stays on "Phone's language" until they really switch.
 */
@Composable
fun LanguageSwitch(onLanguageChange: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    val shown = shownLanguage()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(painterResource(LLIcons.Language), contentDescription = stringResource(R.string.settings_language))
        SingleChoiceSegmentedButtonRow {
            choices.forEachIndexed { index, language ->
                SegmentedButton(
                    selected = language == shown,
                    onClick = { if (language != shown) onLanguageChange(language) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                    colors = chosenSegmentColors(),
                ) {
                    Text(nameOf(language))
                }
            }
        }
    }
}

/** A top-bar button naming the other language: "বাংলা" on English screens, "English" on Bangla ones. */
@Composable
fun LanguageToggle(onLanguageChange: (AppLanguage) -> Unit) {
    val other = if (shownLanguage() == AppLanguage.BANGLA) AppLanguage.ENGLISH else AppLanguage.BANGLA
    TextButton(onClick = { onLanguageChange(other) }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
        Icon(
            painterResource(LLIcons.Language),
            contentDescription = stringResource(R.string.settings_language),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(nameOf(other))
    }
}
