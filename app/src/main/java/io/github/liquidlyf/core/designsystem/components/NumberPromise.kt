package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * VT-14: what happens to a donor's phone number, in the same words everywhere ("About you",
 * Become a donor's List me step, the offer sheet), in readable text with a lock. As small grey
 * lines worded two ways, one tester thought donors' numbers are public and another found
 * "never shown" and "goes to the requester" contradictory.
 */
@Composable
fun NumberPromise(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FirstLineIcon(LLIcons.Lock, style, tint = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.number_promise), style = style)
    }
}
