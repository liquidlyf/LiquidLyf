package io.github.liquidlyf.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.push.AlertPrefs
import io.github.liquidlyf.core.data.push.AlertPrefsStore
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationPrefsUiState(val prefs: AlertPrefs = AlertPrefs(), val loaded: Boolean = false)

/** Phase 5.10: which notifications this phone gets. A change re-syncs the topics at once. */
@HiltViewModel
class NotificationPrefsViewModel @Inject constructor(
    private val store: AlertPrefsStore,
    private val push: PushTopicManager,
    private val session: SessionManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    val uiState: StateFlow<NotificationPrefsUiState> = store.prefs
        .map { NotificationPrefsUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefsUiState())

    /** Saved and applied in the app's scope, so leaving the screen at once can't stop it. */
    fun set(prefs: AlertPrefs) {
        appScope.launch {
            store.set(prefs)
            (session.state.value as? SessionState.Ready)?.let { push.sync(it.profile) }
        }
    }
}

@Composable
fun NotificationPrefsRoute(onBack: () -> Unit, viewModel: NotificationPrefsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberSafeIntentLauncher()
    NotificationPrefsScreen(
        state = state,
        systemEnabled = rememberNotificationsEnabled(),
        onChange = viewModel::set,
        onOpenSystemSettings = launcher::openNotificationSettings,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationPrefsScreen(
    state: NotificationPrefsUiState,
    systemEnabled: Boolean,
    onChange: (AlertPrefs) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val p = state.prefs
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.title_notification_settings), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (!systemEnabled) {
                    NotificationsOffBanner(onTurnOn = onOpenSystemSettings, modifier = Modifier.padding(horizontal = screenGutter()))
                }
                PrefSwitch(R.string.notif_prefs_alerts_title, R.string.notif_prefs_alerts_summary, p.requestAlerts, enabled = state.loaded) {
                    onChange(p.copy(requestAlerts = it))
                }
                PrefSwitch(R.string.notif_prefs_updates_title, R.string.notif_prefs_updates_summary, p.updates, enabled = state.loaded) {
                    onChange(p.copy(updates = it))
                }
                PrefSwitch(R.string.notif_prefs_reminders_title, R.string.notif_prefs_reminders_summary, p.reminders, enabled = state.loaded) {
                    onChange(p.copy(reminders = it))
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_prefs_system_title)) },
                    supportingContent = { Text(stringResource(R.string.notif_prefs_system_summary)) },
                    leadingContent = { Icon(painterResource(LLIcons.Settings), contentDescription = null) },
                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget).clickable(onClick = onOpenSystemSettings),
                )
            }
        }
    }
}

/** The whole row toggles, for TalkBack too (like Home's availability switch). */
@Composable
private fun PrefSwitch(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** True while the app may post notifications; checked again each time the screen comes back. */
@Composable
fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(context) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    return enabled
}

/** Phase 5.10: shown on Home (and here) while notifications are off for the app. */
@Composable
fun NotificationsOffBanner(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.notif_off_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notif_off_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onTurnOn, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.notif_off_turn_on))
            }
        }
    }
}
