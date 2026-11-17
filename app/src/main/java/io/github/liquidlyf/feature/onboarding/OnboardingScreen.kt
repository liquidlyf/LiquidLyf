package io.github.liquidlyf.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LARGE_FONT_SCALE
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.StackedLogo
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.feature.settings.LanguageSwitch
import kotlinx.coroutines.launch

private data class OnboardingPage(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

private val pages = listOf(
    OnboardingPage(LLIcons.Donors, R.string.onboarding_find_title, R.string.onboarding_find_body),
    OnboardingPage(LLIcons.Notifications, R.string.onboarding_alert_title, R.string.onboarding_alert_body),
    // The "never pay for blood" pledge (doc 06 §4.6): paid donation is a known scam in Bangladesh.
    OnboardingPage(LLIcons.Block, R.string.onboarding_money_title, R.string.onboarding_money_body),
    // 8.3: the short medical disclaimer, which everyone sees before using the app (Skip leads here).
    OnboardingPage(LLIcons.Hospital, R.string.onboarding_medical_title, R.string.onboarding_medical_body),
)

/**
 * The slides, shown once on the first launch (Phase 3.1). Skip or finish both go to sign-in.
 * Every page offers English or বাংলা at the top, so a new user can read them in Bangla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit, onLanguageChange: (AppLanguage) -> Unit = {}) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pagerState.currentPage == pages.lastIndex
    // One scroll position per page, so the button knows when the page still has text below (D's re-audit).
    val scrolls = remember { List(pages.size) { ScrollState(0) } }
    val moreBelow = scrolls[pagerState.currentPage].canScrollForward

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = screenGutter(), vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // With very large text, Skip wraps under the language switch instead of being cut off.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageSwitch(onLanguageChange)
                if (!last) {
                    TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pages.lastIndex) } }) {
                        Text(stringResource(R.string.action_skip))
                    }
                }
            }
            val largeText = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                val position = stringResource(R.string.onboarding_page, index + 1, pages.size)
                // A11Y-04: each page scrolls, so with large text the medical disclaimer is read to its end
                // ("…call 999") before "I understand"; centred when it fits.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrolls[index])
                            .heightIn(min = maxHeight)
                            .padding(vertical = Spacing.sm)
                            .semantics { contentDescription = position },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // BRAND-01: the welcome page names the app, with the big stacked logo.
                        if (index == 0) StackedLogo() else Box(
                            Modifier
                                .size(if (largeText) 64.dp else 112.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(page.icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(if (largeText) 32.dp else 56.dp),
                            )
                        }
                        Spacer(Modifier.size(if (largeText) Spacing.md else Spacing.xl))
                        Text(
                            stringResource(page.title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 480.dp),
                        )
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            stringResource(page.body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 480.dp),
                        )
                    }
                    // Text below the fold (large text): a fade and "More below", so nobody misses the
                    // end of the page (D's re-audit: "I understand" could come before "call 999").
                    if (scrolls[index].canScrollForward) MoreBelow(Modifier.align(Alignment.BottomCenter))
                }
            }
            PageDots(count = pages.size, current = pagerState.currentPage)
            Spacer(Modifier.size(Spacing.lg))
            LLPrimaryButton(
                // The disclaimer is agreed to only once it has been seen to its end.
                text = stringResource(
                    when {
                        last && moreBelow -> R.string.action_read_to_end
                        last -> R.string.action_i_understand
                        else -> R.string.action_next
                    },
                ),
                onClick = {
                    val scroll = scrolls[pagerState.currentPage]
                    when {
                        last && scroll.canScrollForward -> scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                        last -> onFinished()
                        else -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
    }
}

/** A fade over the bottom of the page and "More below ↓", while the page has more text. */
@Composable
private fun MoreBelow(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface, surface)))
            .padding(top = Spacing.lg, bottom = Spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(stringResource(R.string.onboarding_more_below), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(
                painterResource(LLIcons.ArrowBack),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).rotate(-90f),
            )
        }
    }
}

/** Decorative: TalkBack hears "Page 2 of 3" on the page itself. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
