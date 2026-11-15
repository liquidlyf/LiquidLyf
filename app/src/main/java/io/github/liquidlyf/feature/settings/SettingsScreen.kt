package io.github.liquidlyf.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.screenGutter
import kotlinx.coroutines.launch

/** Phase 8.1: every setting here changes real behaviour and is saved. */
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
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
        onLanguageChange = viewModel::setLanguage,
        onListedChange = viewModel::setListed,
        onAvailableChange = viewModel::setAvailable,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    var askListing by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { LLTopBar(title = stringResource(R.string.title_settings), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val gutter = screenGutter()
            val sectionPadding = Modifier.padding(start = gutter, end = gutter, top = Spacing.md)
            SectionHeader(stringResource(R.string.settings_theme), Modifier.padding(horizontal = gutter))
            Choices(ThemeMode.entries, uiState.themeMode, onThemeModeChange) { stringResource(it.labelRes) }

            // 8.1: the app's own language; Android 13+ shows the same choice in the phone's app settings.
            SectionHeader(stringResource(R.string.settings_language), sectionPadding)
            Choices(AppLanguage.entries, uiState.language, onLanguageChange) { stringResource(it.labelRes) }

            // Phase 5.10: which notifications this phone gets.
            SectionHeader(stringResource(R.string.settings_notifications), sectionPadding)
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_notification_settings)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_summary)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenNotificationSettings),
            )

            // 8.1 Privacy: being listed is consent, and can be withdrawn here at any time.
            SectionHeader(stringResource(R.string.settings_privacy), sectionPadding)
            if (uiState.listed != null) {
                SwitchRow(
                    title = stringResource(R.string.settings_list_me),
                    summary = stringResource(if (uiState.bloodGroup == null) R.string.settings_list_me_needs_group else R.string.settings_list_me_summary),
                    checked = uiState.listed,
                    enabled = !uiState.saving && (uiState.listed || uiState.bloodGroup != null),
                    onChange = { on -> if (on) askListing = true else onListedChange(false) },
                )
                if (uiState.listed) {
                    SwitchRow(
                        title = stringResource(R.string.home_ready_to_donate),
                        summary = stringResource(if (uiState.available) R.string.home_ready_on else R.string.home_ready_off),
                        checked = uiState.available,
                        enabled = !uiState.saving,
                        onChange = onAvailableChange,
                    )
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_blocked_people)) },
                supportingContent = { Text(stringResource(R.string.settings_blocked_summary)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenBlockedPeople),
            )

            SectionHeader(stringResource(R.string.settings_safety), sectionPadding)
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_safety_tips)) },
                supportingContent = { Text(stringResource(R.string.settings_safety_tips_summary)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSafetyTips),
            )

            // Logout (BUG-003): alerts stop, the session ends and the gate clears the back stack.
            SectionHeader(stringResource(R.string.settings_account), sectionPadding)
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_edit_profile)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onEditProfile),
            )
            if (uiState.hasPassword) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_change_password)) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onChangePassword),
                )
            } else {
                // P3: a Google-only account can add a password; then "Change password" shows instead.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_add_password)) },
                    supportingContent = { Text(stringResource(R.string.settings_add_password_summary)) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAddPassword),
                )
            }
            if (uiState.admin) {
                // 7.5: only with the admin claim (the rules check it too).
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_moderation)) },
                    supportingContent = { Text(stringResource(R.string.settings_moderation_summary)) },
                    leadingContent = { Icon(painterResource(LLIcons.Shield), contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenModeration),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_sign_out)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onSignOut),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_delete_account), color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onDeleteAccount),
            )

            if (showDeveloperOptions) {
                SectionHeader(stringResource(R.string.settings_developer), sectionPadding)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_component_gallery)) },
                    supportingContent = { Text(stringResource(R.string.settings_component_gallery_summary)) },
                    leadingContent = { Icon(painterResource(LLIcons.Palette), contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenComponentGallery),
                )
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    val gutter = screenGutter()
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = Spacing.xs),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option))
            }
        }
    }
}

/** 8.5: the whole row toggles, and TalkBack reads it as one switch. */
@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
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
