package io.github.liquidlyf.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.components.DialogBody
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.BloodGroup

/**
 * Phase 8.1: being listed is consent (PDPA 2026), so turning it on asks with the same words as
 * profile setup. Used by Settings → Privacy and by Home's "List me".
 */
@Composable
fun ListMeDialog(bloodGroup: BloodGroup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_me_title)) },
        text = {
            DialogBody {
                Text(stringResource(R.string.setup_consent_question, stringResource(bloodGroup.labelRes)))
                Text(stringResource(R.string.setup_consent_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.consent_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.consent_not_now)) } },
    )
}
