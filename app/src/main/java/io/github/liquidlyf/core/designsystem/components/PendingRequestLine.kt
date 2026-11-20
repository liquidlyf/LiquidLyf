package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * D31: while a shared request waits on this phone, Sign in and setup's first page say so ("After
 * this, we'll open the blood request you were sent."): without it, a long sign-up feels like the
 * request was lost.
 */
@Composable
fun PendingRequestLine(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FirstLineIcon(LLIcons.Mail, style, tint = colors.primary)
        Text(stringResource(R.string.pending_request_line), style = style, color = colors.onSecondaryContainer)
    }
}
