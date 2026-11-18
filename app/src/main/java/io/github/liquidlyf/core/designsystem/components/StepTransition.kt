package io.github.liquidlyf.core.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * A form's steps, sliding sideways as you go forward or back (MOTION-01, M3 shared axis X, 250 ms).
 * With "Remove animations" on, the step simply changes.
 */
@Composable
fun StepContent(step: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.(Int) -> Unit) {
    AnimatedContent(
        targetState = step,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            val forward = targetState > initialState
            (slideInHorizontally(tween(STEP_MS)) { w -> (if (forward) 1 else -1) * w / 10 } + fadeIn(tween(STEP_MS)))
                .togetherWith(slideOutHorizontally(tween(STEP_MS)) { w -> (if (forward) -1 else 1) * w / 10 } + fadeOut(tween(STEP_MS / 2)))
        },
        label = "step",
    ) { shown ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content(shown) }
    }
}

private const val STEP_MS = 250
