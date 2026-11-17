package io.github.liquidlyf.feature.request.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.RequestCard
import io.github.liquidlyf.core.designsystem.components.SegmentedChoices
import io.github.liquidlyf.core.designsystem.components.StatusPill
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
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.nav_requests)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            OfflineBanner(online)
            // A long label wraps to a second line instead of losing words (in one line, the Bangla
            // "আমার অনুরোধ" showed only "আমার"); with 200% text the tabs scroll sideways.
            SegmentedChoices(
                options = RequestsTab.entries,
                selected = state.tab,
                onSelect = onSelectTab,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = screenGutter(), vertical = Spacing.xs),
            ) { stringResource(it.labelRes) }
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
                                EmptyState(LLIcons.Favorite, stringResource(R.string.requests_nearby_empty_title), stringResource(R.string.requests_nearby_empty_body))
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
                        listFooter(list, onRefresh, onLoadMore)
                    }
                }
            }
        }
    }
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
private fun LazyListScope.listFooter(list: PagedList<*>, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    when {
        list.error != null && list.items.isEmpty() -> item(key = "error") {
            ErrorState(onRetry = onRetry, body = stringResource(list.error))
        }
        !list.loaded || (list.loading && !list.refreshing) -> item(key = "loading") { LoadingState(count = if (list.loaded) 1 else 3) }
        !list.endReached -> item(key = "more") { LaunchedEffect(list.items.size) { onLoadMore() } }
    }
}
