package io.github.liquidlyf.feature.request.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.CapFontScale
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLExtendedFab
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.MAX_LABEL_SCALE
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.RequestCard
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.isLargeText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.BloodRequest

@Composable
fun RequestsRoute(
    onOpenRequest: (String) -> Unit,
    onCreateRequest: () -> Unit,
    viewModel: RequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    RequestsScreen(state, online, viewModel::selectTab, viewModel::refresh, viewModel::loadMore, onOpenRequest, onCreateRequest)
}

/**
 * Nearby / My requests / My offers as Material tabs (A11Y-02), three equal ones at every text size.
 * Their text grows up to 1.5x and a long label takes a second line between words (P2-5: at 200% the
 * sideways-scrolling row showed cut words, "earby", "আমার স"). The tab grows to fit 2 lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestsTabs(selected: RequestsTab, onSelect: (RequestsTab) -> Unit, modifier: Modifier = Modifier) {
    PrimaryTabRow(selectedTabIndex = RequestsTab.entries.indexOf(selected), modifier = modifier) {
        RequestsTab.entries.forEach { tab ->
            Tab(selected = tab == selected, onClick = { onSelect(tab) }, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                CapFontScale(MAX_LABEL_SCALE) {
                    Text(
                        stringResource(tab.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestsScreen(
    state: RequestsUiState,
    online: Boolean,
    onSelectTab: (RequestsTab) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onCreateRequest: () -> Unit,
) {
    // NAV-03: the same Request blood as on Home; with large text a full-width bar, not floating over a card (UI-08).
    val actionAsBar = isLargeText()
    Scaffold(
        topBar = { LLTopBar(title = stringResource(R.string.nav_requests)) },
        floatingActionButton = {
            if (!actionAsBar) LLExtendedFab(text = stringResource(R.string.action_request_blood), icon = LLIcons.Add, onClick = onCreateRequest)
        },
        bottomBar = {
            if (actionAsBar) LLBottomBar(stringResource(R.string.action_request_blood), onCreateRequest, primaryIcon = LLIcons.Add)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            OfflineBanner(online)
            RequestsTabs(state.tab, onSelectTab, Modifier.widthIn(max = 640.dp).fillMaxWidth())
            val list = state.list(state.tab)
            PullToRefreshBox(isRefreshing = list.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        when (state.tab) {
                            RequestsTab.NEARBY -> requestItems(state, state.nearby, onOpenRequest) {
                                // STATE-02: a button too, not only the pull-down gesture.
                                EmptyState(
                                    LLIcons.Favorite, stringResource(R.string.requests_nearby_empty_title), stringResource(R.string.requests_nearby_empty_body),
                                    actionLabel = stringResource(R.string.action_refresh), onAction = onRefresh,
                                )
                            }
                            RequestsTab.MINE -> requestItems(state, state.mine, onOpenRequest) {
                                EmptyState(
                                    LLIcons.Requests, stringResource(R.string.requests_mine_empty_title), stringResource(R.string.requests_mine_empty_body),
                                    actionLabel = stringResource(R.string.action_request_blood), onAction = onCreateRequest,
                                )
                            }
                            RequestsTab.OFFERS -> offerItems(state, onOpenRequest) {
                                EmptyState(
                                    LLIcons.VolunteerActivism, stringResource(R.string.requests_offers_empty_title), stringResource(R.string.requests_offers_empty_body),
                                    actionLabel = stringResource(R.string.requests_see_nearby), onAction = { onSelectTab(RequestsTab.NEARBY) },
                                )
                            }
                        }
                        listFooter(list, state.tab.loadErrorRes, onRefresh, onLoadMore)
                        // Room for the floating button, so it never covers the last card.
                        if (!actionAsBar) item(key = "fab-space") { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}

/** COPY-04: what didn't load, per tab. */
private val RequestsTab.loadErrorRes: Int
    get() = when (this) {
        RequestsTab.NEARBY -> R.string.home_load_error
        RequestsTab.MINE -> R.string.requests_mine_load_error
        RequestsTab.OFFERS -> R.string.requests_offers_load_error
    }

private val RequestsTab.labelRes: Int
    get() = when (this) {
        RequestsTab.NEARBY -> R.string.requests_tab_nearby
        RequestsTab.MINE -> R.string.requests_tab_mine
        RequestsTab.OFFERS -> R.string.requests_tab_offers
    }

private fun LazyListScope.requestItems(
    state: RequestsUiState,
    list: PagedList<BloodRequest>,
    onOpenRequest: (String) -> Unit,
    empty: @Composable () -> Unit,
) {
    if (list.loaded && list.items.isEmpty() && list.error == null) item(key = "empty") { empty() }
    items(list.items, key = { it.id }) { r ->
        val now by rememberNow()
        RequestCard(
            request = r,
            placeText = state.geo?.placeLabel(r.district, r.upazila, currentAppLocale()) ?: r.district,
            now = now,
            onClick = { onOpenRequest(r.id) },
        )
    }
}

private fun LazyListScope.offerItems(state: RequestsUiState, onOpenRequest: (String) -> Unit, empty: @Composable () -> Unit) {
    val list = state.offers
    if (list.loaded && list.items.isEmpty() && list.error == null) item(key = "empty") { empty() }
    items(list.items, key = { it.response.requestId }) { item ->
        val r = item.request
        val footer: @Composable () -> Unit = {
            // The pill moves below the label when both don't fit; squeezed beside it, its word broke
            // in two at 200% text ("Offer/ed").
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.detail_your_offer), style = MaterialTheme.typography.labelLarge)
                StatusPill(item.response.status)
            }
        }
        if (r == null) {
            LLCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.detail_missing_title), style = MaterialTheme.typography.titleMedium)
                    footer()
                }
            }
        } else {
            val now by rememberNow()
            RequestCard(
                request = r,
                placeText = state.geo?.placeLabel(r.district, r.upazila, currentAppLocale()) ?: r.district,
                now = now,
                onClick = { onOpenRequest(r.id) },
                footer = footer,
            )
        }
    }
}

/** First load, errors and the next page, which loads when this footer scrolls into view. */
private fun LazyListScope.listFooter(list: PagedList<*>, @StringRes title: Int, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    when {
        list.error != null && list.items.isEmpty() -> item(key = "error") {
            ErrorState(onRetry = onRetry, title = stringResource(title), body = stringResource(list.error))
        }
        !list.loaded || (list.loading && !list.refreshing) -> item(key = "loading") { LoadingState(count = if (list.loaded) 1 else 3) }
        !list.endReached -> item(key = "more") { LaunchedEffect(list.items.size) { onLoadMore() } }
    }
}
