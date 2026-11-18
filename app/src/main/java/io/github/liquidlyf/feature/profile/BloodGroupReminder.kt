package io.github.liquidlyf.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.DialogText
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton

/**
 * Shown while the blood group is unknown (P1): such users can request blood, but can't be listed
 * as donors or offer until they add it. "How to find out" explains testing.
 */
@Composable
fun BloodGroupReminder(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(painterResource(LLIcons.BloodDrop), contentDescription = null)
                Text(stringResource(R.string.reminder_add_blood_group_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.reminder_add_blood_group_body), style = MaterialTheme.typography.bodyMedium)
            LLPrimaryButton(text = stringResource(R.string.action_add_blood_group), onClick = onAdd)
            TextButton(onClick = { showHelp = true }) { Text(stringResource(R.string.action_how_to_find_out)) }
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.setup_blood_unknown)) },
            text = { DialogText(stringResource(R.string.setup_blood_unknown_body)) },
        )
    }
}
