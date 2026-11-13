package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * An extended FAB whose label TalkBack reads. Material 3's ExtendedFloatingActionButton (1.4.0)
 * doesn't merge its text into the button node, so the button was announced as just "Button"
 * (found by the Phase 4 navigation test); the label is set on the button itself.
 */
@Composable
fun LLExtendedFab(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        text = { Text(text) },
        modifier = modifier.semantics { contentDescription = text },
    )
}
