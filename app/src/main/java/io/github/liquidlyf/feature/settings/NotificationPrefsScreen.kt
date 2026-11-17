package io.github.liquidlyf.feature.settings

import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.github.liquidlyf.core.designsystem.components.LLNoticeCard
import io.github.liquidlyf.core.designsystem.components.LLSwitchRow
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.NoticeTone
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.notifications.NotificationChannels
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
        // This screen is about the app-wide block; the channels are in the phone's own settings.
        systemEnabled = rememberNotificationsEnabled(alertsChannel = false),
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
                PrefSwitch(R.string.notif_prefs_alerts_title, R.string.notif_prefs_alerts_summary, p.requestAlerts, enabled = state.loaded && systemEnabled) {
                    onChange(p.copy(requestAlerts = it))
                }
                PrefSwitch(R.string.notif_prefs_updates_title, R.string.notif_prefs_updates_summary, p.updates, enabled = state.loaded && systemEnabled) {
                    onChange(p.copy(updates = it))
                }
                PrefSwitch(R.string.notif_prefs_reminders_title, R.string.notif_prefs_reminders_summary, p.reminders, enabled = state.loaded && systemEnabled) {
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

/** The shared switch row (DS-01), at the list's gutter like the ListItems around it. */
@Composable
private fun PrefSwitch(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    LLSwitchRow(
        stringResource(title), checked, onChange,
        summary = stringResource(summary), enabled = enabled, horizontalPadding = screenGutter(),
    )
}

/**
 * True while request alerts can reach this phone; checked again each time the screen comes back.
 * [alertsChannel]: the "Urgent blood requests" channel counts too, since it can be turned off on its
 * own in the phone's settings (D's re-audit: only the app-wide block was noticed).
 */
@Composable
fun rememberNotificationsEnabled(alertsChannel: Boolean = true): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(notificationsEnabled(context, alertsChannel)) }
    LifecycleResumeEffect(context) {
        enabled = notificationsEnabled(context, alertsChannel)
        onPauseOrDispose {}
    }
    return enabled
}

private fun notificationsEnabled(context: Context, alertsChannel: Boolean): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    if (!alertsChannel) return true
    // Below Android 8 there are no channels (null): the app-wide switch is all there is.
    val urgent = manager.getNotificationChannelCompat(NotificationChannels.URGENT_REQUESTS) ?: return true
    return urgent.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/**
 * Phase 5.10: shown on Home (and here) while notifications are off for the app. A warning with
 * a bell-off icon and a filled "Turn on" (STATE-04: the old pink card with a small text button was
 * easy to miss, and without notifications the app silently doesn't work for this person).
 */
@Composable
fun NotificationsOffBanner(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    LLNoticeCard(
        text = stringResource(R.string.notif_off_body),
        icon = LLIcons.NotificationsOff,
        tone = NoticeTone.Warning,
        title = stringResource(R.string.notif_off_title),
        modifier = modifier,
        action = {
            Button(onClick = onTurnOn, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.notif_off_turn_on))
            }
        },
    )
}
