package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * A dialog's text that scrolls when it doesn't fit (P2-1: in Bangla at 200% text the List me dialog's
 * privacy promise was cut off above its buttons). While there's more above or below, a thin line shows at
 * that edge, as Material's dialogs do. Every AlertDialog's `text` uses this or [DialogText].
 */
@Composable
fun DialogBody(modifier: Modifier = Modifier, spacing: Dp = Spacing.sm, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(modifier) {
        if (scroll.canScrollBackward) HorizontalDivider()
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        if (scroll.canScrollForward) HorizontalDivider()
    }
}

/** A dialog's one paragraph, scrolling when it doesn't fit (see [DialogBody]). */
@Composable
fun DialogText(text: String) {
    DialogBody { Text(text) }
}
