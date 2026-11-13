package io.github.liquidlyf.feature.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.screenGutter

/** A button on a placeholder screen that moves through the navigation graph. */
@Immutable
data class PlaceholderLink(val label: String, val onClick: () -> Unit)

/**
 * Phase 1 stand-in for a screen that a later phase builds. It has the real title, top bar
 * and insets, says which phase brings the screen, and offers [links] to walk the
 * navigation graph. Taps are ignored while a screen transition runs, so a double tap can't
 * open a screen twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    phase: Int,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    detail: String? = null,
    links: List<PlaceholderLink> = emptyList(),
    topBarActions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            LLTopBar(
                title = title,
                onBack = onBack?.let { dropUnlessResumed(block = it) },
                actions = topBarActions,
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenGutter(), vertical = Spacing.lg),
        ) {
            EmptyState(
                icon = LLIcons.BloodDrop,
                title = title,
                body = stringResource(R.string.placeholder_body, phase),
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            links.forEach { link ->
                OutlinedButton(
                    onClick = dropUnlessResumed(block = link.onClick),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouchTarget),
                ) {
                    Text(link.label)
                }
            }
        }
    }
}
