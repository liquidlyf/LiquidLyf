package io.github.liquidlyf.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLSwitchRow
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.SegmentedChoices
import io.github.liquidlyf.core.designsystem.components.isLargeText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateText
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.about.versionLabel
import io.github.liquidlyf.feature.home.readyText
import kotlinx.coroutines.launch

/** Phase 8.1: every setting here changes real behaviour and is saved. N5 (D8, 18 Nov) sets the order. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenComponentGallery: () -> Unit,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAddPassword: () -> Unit = {},
    onOpenSafetyTips: () -> Unit = {},
    onOpenBlockedPeople: () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHowMatchingWorks: () -> Unit = {},
    onBecomeDonor: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    val launcher = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e -> if (e is SettingsEvent.Message) launch { snackbar.showSnackbar(resources.getString(e.text)) } }
    }
    SettingsContent(
        uiState = uiState,
        onBack = dropUnlessResumed(block = onBack),
        onThemeModeChange = viewModel::setThemeMode,
        onOpenComponentGallery = dropUnlessResumed(block = onOpenComponentGallery),
        onEditProfile = dropUnlessResumed(block = onEditProfile),
        onChangePassword = dropUnlessResumed(block = onChangePassword),
        onDeleteAccount = dropUnlessResumed(block = onDeleteAccount),
        onSignOut = dropUnlessResumed(block = onSignOut),
        showDeveloperOptions = BuildConfig.DEBUG,
        onOpenNotificationSettings = dropUnlessResumed(block = onOpenNotificationSettings),
        onAddPassword = dropUnlessResumed(block = onAddPassword),
        onOpenSafetyTips = dropUnlessResumed(block = onOpenSafetyTips),
        onOpenBlockedPeople = dropUnlessResumed(block = onOpenBlockedPeople),
        onOpenModeration = dropUnlessResumed(block = onOpenModeration),
        onOpenAbout = dropUnlessResumed(block = onOpenAbout),
        onOpenHowMatchingWorks = dropUnlessResumed(block = onOpenHowMatchingWorks),
        onShareApp = { launcher.shareText(resources.getString(R.string.share_app_text, Links.download(locale))) },
        onLanguageChange = viewModel::setLanguage,
        onListedChange = viewModel::setListed,
        onAvailableChange = viewModel::setAvailable,
        onBecomeDonor = dropUnlessResumed(block = onBecomeDonor),
    )
}

@Composable
internal fun SettingsContent(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenComponentGallery: () -> Unit,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    showDeveloperOptions: Boolean,
    modifier: Modifier = Modifier,
    onOpenNotificationSettings: () -> Unit = {},
    onAddPassword: () -> Unit = {},
    onOpenSafetyTips: () -> Unit = {},
    onOpenBlockedPeople: () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    onLanguageChange: (AppLanguage) -> Unit = {},
    onListedChange: (Boolean) -> Unit = {},
    onAvailableChange: (Boolean) -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHowMatchingWorks: () -> Unit = {},
    onShareApp: () -> Unit = {},
    onBecomeDonor: () -> Unit = {},
) {
    var askListing by rememberSaveable { mutableStateOf(false) }
    var askSignOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { SettingsTopBar(onBack = onBack, onSignOut = { askSignOut = true }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // N5: Language first, its title in both languages, so someone who opened the app in the
            // wrong language still finds it at the top.
            GroupHeader(bilingual(stringResource(R.string.settings_language_both)), first = true)
            Choices(AppLanguage.entries, uiState.language, onLanguageChange) { stringResource(it.labelRes) }

            // Phase 5.10: which notifications this phone gets.
            GroupHeader(stringResource(R.string.settings_notifications))
            SettingsRow(
                icon = LLIcons.Notifications,
                title = stringResource(R.string.title_notification_settings),
                summary = stringResource(R.string.settings_notifications_summary),
                onClick = onOpenNotificationSettings,
            )

            // 8.1: being listed is consent, and can be withdrawn here at any time. Each line is true
            // for its state (COPY-04), like Profile's Donor card.
            if (uiState.listed != null && !uiState.isDonor) {
                // D1: not a donor yet, so no switches: the way in instead.
                GroupHeader(stringResource(R.string.settings_donor_status))
                SettingsRow(LLIcons.Favorite, stringResource(R.string.action_become_donor), stringResource(R.string.home_become_donor_body), onBecomeDonor)
            } else if (uiState.listed != null) {
                GroupHeader(stringResource(R.string.settings_donor_status))
                SwitchRow(
                    icon = LLIcons.Donors,
                    title = stringResource(R.string.settings_list_me),
                    summary = stringResource(
                        when {
                            uiState.bloodGroup == null -> R.string.settings_list_me_needs_group
                            uiState.listed -> R.string.profile_listed_on
                            else -> R.string.profile_listed_off
                        },
                    ),
                    checked = uiState.listed,
                    enabled = !uiState.saving && (uiState.listed || uiState.bloodGroup != null),
                    onChange = { on -> if (on) askListing = true else onListedChange(false) },
                )
                if (uiState.listed) {
                    val notificationsOn = rememberNotificationsEnabled()
                    val again = uiState.alertsAgainOn
                    SwitchRow(
                        icon = LLIcons.BloodDrop,
                        title = stringResource(R.string.home_ready_to_donate),
                        summary = if (uiState.available && notificationsOn && again != null) {
                            stringResource(R.string.profile_alerts_again_on, dateText(again))
                        } else {
                            stringResource(readyText(uiState.available, notificationsOn))
                        },
                        checked = uiState.available,
                        enabled = !uiState.saving,
                        onChange = onAvailableChange,
                    )
                }
            }

            GroupHeader(stringResource(R.string.settings_theme))
            Choices(ThemeMode.entries, uiState.themeMode, onThemeModeChange) { stringResource(it.labelRes) }

            // N5: Blocked people moves here from Privacy.
            GroupHeader(stringResource(R.string.settings_safety))
            SettingsRow(LLIcons.Shield, stringResource(R.string.title_safety_tips), stringResource(R.string.settings_safety_tips_summary), onOpenSafetyTips)
            SettingsRow(LLIcons.Help, stringResource(R.string.title_how_matching_works), stringResource(R.string.settings_how_matching_summary), onOpenHowMatchingWorks)
            SettingsRow(LLIcons.Block, stringResource(R.string.title_blocked_people), stringResource(R.string.settings_blocked_summary), onOpenBlockedPeople)

            GroupHeader(stringResource(R.string.settings_account))
            SettingsRow(LLIcons.Edit, stringResource(R.string.title_edit_profile), onClick = onEditProfile)
            if (uiState.hasPassword) {
                SettingsRow(LLIcons.Key, stringResource(R.string.title_change_password), onClick = onChangePassword)
            } else {
                // P3: a Google-only account can add a password; then "Change password" shows instead.
                SettingsRow(LLIcons.Key, stringResource(R.string.title_add_password), stringResource(R.string.settings_add_password_summary), onAddPassword)
            }
            if (uiState.admin) {
                // 7.5: only with the admin claim (the rules check it too).
                SettingsRow(LLIcons.Flag, stringResource(R.string.title_moderation), stringResource(R.string.settings_moderation_summary), onOpenModeration)
            }

            // 8.1 About: the legal pages, the licences, the source code (AGPL) and how to reach us.
            // Its line says what's inside (UI-07); Share LiquidLyf moved here from Profile (N5).
            GroupHeader(stringResource(R.string.settings_about))
            SettingsRow(LLIcons.Info, stringResource(R.string.title_about), stringResource(R.string.settings_about_summary, versionLabel()), onOpenAbout)
            SettingsRow(LLIcons.Share, stringResource(R.string.action_share_app), stringResource(R.string.settings_share_summary), onShareApp)

            if (showDeveloperOptions) {
                GroupHeader(stringResource(R.string.settings_developer))
                SettingsRow(
                    LLIcons.Palette,
                    stringResource(R.string.settings_component_gallery),
                    stringResource(R.string.settings_component_gallery_summary),
                    onOpenComponentGallery,
                )
            }

            // N5: Delete alone at the end, after a line, in red with a bin icon.
            HorizontalDivider(Modifier.padding(top = Spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                icon = LLIcons.Delete,
                title = stringResource(R.string.title_delete_account),
                onClick = onDeleteAccount,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(Spacing.md))
        }
    }
    val group = uiState.bloodGroup
    if (askListing && group != null) {
        ListMeDialog(
            bloodGroup = group,
            onConfirm = {
                askListing = false
                onListedChange(true)
            },
            onDismiss = { askListing = false },
        )
    }
    if (askSignOut) {
        SignOutDialog(
            email = uiState.email,
            onConfirm = {
                askSignOut = false
                onSignOut()
            },
            onDismiss = { askSignOut = false },
        )
    }
}

/**
 * The bar with Sign out in its corner (N5, the maintainer's idea). The word stays next to the icon:
 * our rule for people who read little is "icons with text, never alone". With large text the title
 * takes its own line, so "Sign out" keeps its word and nothing is cut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit, onSignOut: () -> Unit) {
    val title = stringResource(R.string.title_settings)
    if (!isLargeText()) {
        LLTopBar(title = title, onBack = onBack, actions = { SignOutButton(onSignOut, Modifier.padding(end = Spacing.xs)) })
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(LLIcons.ArrowBack), contentDescription = stringResource(R.string.action_back))
                }
                Spacer(Modifier.weight(1f))
                SignOutButton(onSignOut, Modifier.padding(end = Spacing.xs))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = screenGutter(), end = screenGutter(), bottom = Spacing.sm)
                    .semantics { heading() },
            )
        }
    }
}

/** A plain outlined button, not red: signing out is easy to undo by signing in. */
@Composable
private fun SignOutButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(
            painterResource(LLIcons.Logout),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "Are you sure you want to sign out?" with Yes and No, as the maintainer asked (N5). */
@Composable
private fun SignOutDialog(email: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.sign_out_body))
                email?.let { Text(stringResource(R.string.sign_out_account, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.answer_yes)) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.answer_no)) } },
    )
}

/**
 * "Language · ভাষা" with each half tagged with its language, so TalkBack reads the Bangla word with
 * a Bangla voice (D's re-audit: an English voice garbled it).
 */
private fun bilingual(text: String): AnnotatedString = buildAnnotatedString {
    text.split(" · ").forEachIndexed { i, part ->
        if (i > 0) append(" · ")
        val bangla = part.any { it in '\u0980'..'\u09FF' } // the Bangla block
        withStyle(SpanStyle(localeList = LocaleList(if (bangla) "bn" else "en"))) { append(part) }
    }
}

/** A group's title: small and grey, so the rows stay what people read (N5). */
@Composable
private fun GroupHeader(text: String, first: Boolean = false) = GroupHeader(AnnotatedString(text), first)

@Composable
private fun GroupHeader(text: AnnotatedString, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = screenGutter(), end = screenGutter(), top = if (first) Spacing.xs else Spacing.lg, bottom = Spacing.xxs)
            .semantics { heading() },
    )
}

/** A row that opens something: an icon next to its words on every row (UI-07, N5). */
@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    color: Color = Color.Unspecified,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = screenGutter(), vertical = Spacing.xs),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    SegmentedChoices(
        options,
        selected,
        onSelect,
        Modifier.padding(horizontal = screenGutter(), vertical = Spacing.xs),
        // "Phone" alone is short enough for one line; TalkBack says "Same as phone" (N5).
        spokenLabel = { option -> if (option == ThemeMode.SYSTEM || option == AppLanguage.SYSTEM) stringResource(R.string.language_same_as_phone) else null },
        label = label,
    )
}

/** The shared switch row (DS-01), at the list's gutter like the rows around it. */
@Composable
private fun SwitchRow(@DrawableRes icon: Int, title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    LLSwitchRow(title, checked, onChange, summary = summary, enabled = enabled, horizontalPadding = screenGutter(), icon = icon)
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.BANGLA -> R.string.language_bangla
    }
