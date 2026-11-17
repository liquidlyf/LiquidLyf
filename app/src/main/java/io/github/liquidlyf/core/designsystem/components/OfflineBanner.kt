package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * "You're offline. Showing saved data." (doc 04 §6), or [text] (a form says what happens to what
 * the person sends). TalkBack announces it when it appears. A calm grey bar (STATE-06: the old
 * inverse colours were a glaring light band in dark theme).
 */
@Composable
fun OfflineBanner(online: Boolean, modifier: Modifier = Modifier, text: String = stringResource(R.string.offline_banner)) {
    if (online) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(LLIcons.WifiOff), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
