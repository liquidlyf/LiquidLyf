package io.github.liquidlyf.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.ButtonLabel
import io.github.liquidlyf.core.designsystem.components.LLCard

/**
 * Not a donor yet (D1, N6): "Want to donate too?" with Become a donor, on Home (in the donor line's
 * place) and on Profile (instead of the Donor card).
 */
@Composable
fun BecomeDonorCard(onBecomeDonor: () -> Unit, modifier: Modifier = Modifier) {
    LLCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(LLIcons.Favorite), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.home_become_donor_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_become_donor_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onBecomeDonor, modifier = Modifier.padding(top = Spacing.xs)) { ButtonLabel(stringResource(R.string.action_become_donor)) }
            }
        }
    }
}
