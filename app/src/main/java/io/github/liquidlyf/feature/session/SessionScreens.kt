package io.github.liquidlyf.feature.session

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher

/** Phase 7: the `banned` claim. The rules refuse every write; the person can sign out or write to us. */
@Composable
fun SuspendedScreen(onSignOut: () -> Unit) {
    val links = rememberSafeIntentLauncher()
    val subject = stringResource(R.string.suspended_mail_subject)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = screenGutter()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                icon = LLIcons.Block,
                title = stringResource(R.string.suspended_title),
                body = stringResource(R.string.suspended_body, Links.SUPPORT_EMAIL),
                actionLabel = stringResource(R.string.action_write_to_us),
                onAction = { links.email(Links.SUPPORT_EMAIL, subject) },
            )
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) }
        }
    }
}

/**
 * A20: sign-out takes a moment (the alert topics are dropped first), so say so right away.
 * Back and taps outside are ignored until it's done.
 */
@Composable
fun SigningOutDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Row(
                Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.signing_out), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Signed in, but the profile can't be read (offline on a new phone): Retry or sign out. */
@Composable
fun SessionProblemScreen(error: AppError, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = screenGutter()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorState(
                onRetry = onRetry,
                title = stringResource(R.string.session_problem_title),
                body = stringResource(error.messageRes),
                icon = if (error == AppError.Network) LLIcons.WifiOff else LLIcons.Error,
            )
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) }
        }
    }
}

/** True on Android 13+ until the user answers; older versions have no notification permission. */
fun needsNotificationPermission(granted: Boolean): Boolean = Build.VERSION.SDK_INT >= 33 && !granted

/**
 * Asked in context, right after profile setup, never at cold start (doc 06 §4.5). "Not now"
 * is fine: everything is still in the app, and Settings can turn alerts on later.
 */
@Composable
fun NotificationPermissionScreen(onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = screenGutter(), vertical = Spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(LLIcons.Notifications),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                stringResource(R.string.notifications_rationale_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(top = Spacing.lg),
            )
            Text(
                stringResource(R.string.notifications_rationale_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(top = Spacing.sm, bottom = Spacing.xl),
            )
            LLPrimaryButton(
                text = stringResource(R.string.action_allow_alerts),
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
                },
                modifier = Modifier.widthIn(max = 480.dp),
            )
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
                Text(stringResource(R.string.action_not_now))
            }
        }
    }
}
