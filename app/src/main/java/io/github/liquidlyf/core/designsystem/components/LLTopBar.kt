package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons

/**
 * The top app bar. It sits below the status bar (edge-to-edge safe). With [onBack] it shows
 * a labelled back arrow, which mirrors in right-to-left layouts. The title is a TalkBack heading.
 * With large text the bar is taller and the title may take two lines, shrinking to fit
 * (A11Y-03: at 200% titles were cut to "Request de…", "অনুরোধের …").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    /** The horizontal logo instead of [title] (Home only, BRAND-01): TalkBack reads it as the app's name, a heading. */
    brand: Boolean = false,
) {
    val large = isLargeText()
    TopAppBar(
        title = {
            if (brand) {
                HorizontalLogo(Modifier.semantics { heading() })
            } else if (large) {
                val style = LocalTextStyle.current
                BasicText(
                    text = title,
                    style = style.copy(color = LocalContentColor.current, lineHeight = 1.25.em),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
                    modifier = Modifier.semantics { heading() },
                )
            } else {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        modifier = modifier,
        expandedHeight = if (large) LARGE_TEXT_BAR_HEIGHT else TopAppBarDefaults.TopAppBarExpandedHeight,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(LLIcons.ArrowBack), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** Room for a two-line title with large text (the normal bar is 64 dp). */
private val LARGE_TEXT_BAR_HEIGHT = 96.dp
