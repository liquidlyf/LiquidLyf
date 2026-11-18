package io.github.liquidlyf.feature.safety

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.DialogBody
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.ReportReason

/**
 * 7.1: one reason and an optional note, for a request or a person (from the request screen, a
 * responder row or a donor card). One report per target per user: the doc id is fixed.
 */
@Composable
fun ReportDialog(@StringRes title: Int, onDismiss: () -> Unit, onReport: (ReportReason, String?) -> Unit) {
    var choice by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            DialogBody(Modifier.selectableGroup(), spacing = Spacing.xxs) {
                ReportReason.entries.forEach { reason ->
                    ReasonRow(stringResource(reason.labelRes), choice == reason) { choice = reason }
                }
                LLTextField(
                    value = note,
                    onValueChange = { note = it.take(Validators.REPORT_NOTE_MAX) },
                    label = stringResource(R.string.detail_report_note),
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { choice?.let { onReport(it, note) } }, enabled = choice != null) { Text(stringResource(R.string.detail_report)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ReasonRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Spacing.xs))
    }
}
