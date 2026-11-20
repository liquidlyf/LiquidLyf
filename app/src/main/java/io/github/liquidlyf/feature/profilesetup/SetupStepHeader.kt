package io.github.liquidlyf.feature.profilesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/** D28: setup's donor path is 4 steps: About you, then Become a donor's 3. */
internal const val SETUP_STEPS = 4

private const val CHEVRON = "chevron"

/**
 * "Step [step] of [total]" with its progress line. [onSkip]: on setup's donor steps, "Need blood
 * now? Skip this" under it (D28), so an emergency is never slowed down.
 */
@Composable
internal fun SetupStepHeader(step: Int, total: Int = SETUP_STEPS, onSkip: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.setup_step, step, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                contentPadding = PaddingValues(start = 0.dp, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
            ) {
                // The chevron is part of the words, so it follows the last one when they wrap (Bangla
                // at 200%) and grows with the text.
                val label = stringResource(R.string.setup_skip_donor)
                Text(
                    buildAnnotatedString {
                        append(label)
                        append(' ')
                        appendInlineContent(CHEVRON, "›")
                    },
                    inlineContent = mapOf(
                        CHEVRON to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                            Icon(painterResource(LLIcons.ChevronRight), contentDescription = null, modifier = Modifier.fillMaxSize())
                        },
                    ),
                    // TalkBack reads the words only.
                    modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) },
                )
            }
        }
    }
}
