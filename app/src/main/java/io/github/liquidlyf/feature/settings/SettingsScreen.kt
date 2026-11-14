package io.github.liquidlyf.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.screenGutter

/** Phase 1 settings: the theme switch (real) and the debug component gallery. Phase 8 adds the rest. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenComponentGallery: () -> Unit,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
) {
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
            SectionHeader(stringResource(R.string.settings_theme), Modifier.padding(horizontal = gutter))
            val modes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = Spacing.xs),
            ) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(stringResource(mode.labelRes))
                    }
                }
            }

            // Phase 5.10: which notifications this phone gets.
            SectionHeader(stringResource(R.string.settings_notifications), Modifier.padding(start = gutter, end = gutter, top = Spacing.md))
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_notification_settings)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_summary)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenNotificationSettings),
            )

            // Logout (BUG-003): alerts stop, the session ends and the gate clears the back stack.
            SectionHeader(stringResource(R.string.settings_account), Modifier.padding(start = gutter, end = gutter, top = Spacing.md))
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_edit_profile)) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onEditProfile),
            )
            if (uiState.hasPassword) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_change_password)) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onChangePassword),
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
                SectionHeader(
                    stringResource(R.string.settings_developer),
                    Modifier.padding(start = gutter, end = gutter, top = Spacing.md),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_component_gallery)) },
                    supportingContent = { Text(stringResource(R.string.settings_component_gallery_summary)) },
                    leadingContent = { Icon(painterResource(LLIcons.Palette), contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenComponentGallery),
                )
            }
        }
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
