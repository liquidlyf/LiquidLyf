package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.AppError

/*
 * The standard screen states (doc 04 §6). Every list has loading, empty and error states.
 */

/** First load: [count] skeleton cards that gently pulse. TalkBack announces "Loading…". */
@Composable
fun LoadingState(modifier: Modifier = Modifier, count: Int = 3) {
    val description = stringResource(R.string.state_loading)
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        repeat(count) {
            // Alpha is read in the draw phase, so the pulse doesn't recompose.
            SkeletonCard(Modifier.graphicsLayer { alpha = pulse })
        }
    }
}

/**
 * A form's first load: field-shaped placeholders (UI-07: list-row skeletons on a form looked like
 * a list was coming). TalkBack announces "Loading…".
 */
@Composable
fun FormLoadingState(modifier: Modifier = Modifier, fields: Int = 3) {
    val description = stringResource(R.string.state_loading)
    val pulse by rememberInfiniteTransition(label = "formSkeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "formSkeletonAlpha",
    )
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = screenGutter(), vertical = Spacing.md)
            .graphicsLayer { alpha = pulse }
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Box(Modifier.fillMaxWidth(0.5f).height(24.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
        repeat(fields) {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
        }
    }
}

@Composable
private fun SkeletonCard(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    LLCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(Spacing.md),
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(block))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
                Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(block))
            }
        }
    }
}

/** Nothing to show: a 48dp icon, a title, one line of help and at most one action. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            // Said when it appears, e.g. after a failed load (D's re-audit).
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            FilledTonalButton(onClick = onAction) { ButtonLabel(actionLabel) }
        }
    }
}

/** Loading failed: the empty-state layout with an error icon and Retry. */
@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_error_title),
    body: String = stringResource(R.string.error_unknown),
    @DrawableRes icon: Int = LLIcons.Error,
) {
    EmptyState(
        icon = icon,
        title = title,
        body = body,
        actionLabel = stringResource(R.string.action_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}

/** [ErrorState] for an [AppError]; offline errors get the no-Wi-Fi icon. [title] says what didn't load. */
@Composable
fun ErrorState(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = stringResource(R.string.state_error_title)) {
    ErrorState(
        onRetry = onRetry,
        title = title,
        body = stringResource(error.messageRes),
        icon = if (error == AppError.Network) LLIcons.WifiOff else LLIcons.Error,
        modifier = modifier,
    )
}
