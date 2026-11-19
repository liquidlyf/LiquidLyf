package io.github.liquidlyf.feature.request.list

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.NetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RequestsTab { NEARBY, MINE, OFFERS }

/**
 * D21: the tab the Requests screen opens on next, asked for just before going there (the limit
 * message's "See my requests"). A new screen takes it before its first load. One kept with the
 * tab's back stack takes it once it's shown again ([RequestsViewModel.takeAskedTab]); only the
 * shown screen does, so a screen kept elsewhere can't take it first.
 */
object RequestsTabRequest {
    private val pending = MutableStateFlow<RequestsTab?>(null)
    internal val asked: StateFlow<RequestsTab?> = pending.asStateFlow()

    fun open(tab: RequestsTab) {
        pending.value = tab
    }

    /** The asked tab, once: the next screen to open keeps its own. */
    internal fun take(): RequestsTab? = pending.getAndUpdate { null }
}

/** One paged list. Plain reads with pull-to-refresh, never a listener (doc 10 §5.1). */
data class PagedList<T>(
    val items: List<T> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    @param:StringRes val error: Int? = null,
)

/** My offer, with the request it's on (null if that request is gone). */
data class OfferItem(val response: DonorResponse, val request: BloodRequest?)

data class RequestsUiState(
    val tab: RequestsTab = RequestsTab.NEARBY,
    val nearby: PagedList<BloodRequest> = PagedList(),
    val mine: PagedList<BloodRequest> = PagedList(),
    val offers: PagedList<OfferItem> = PagedList(),
    val geo: BdGeo? = null,
    /** Marks my own requests in Nearby (VT-22). */
    val myUid: String? = null,
) {
    fun list(tab: RequestsTab): PagedList<*> = when (tab) {
        RequestsTab.NEARBY -> nearby
        RequestsTab.MINE -> mine
        RequestsTab.OFFERS -> offers
    }
}

/**
 * Requests tab (Phase 4.4, doc 04 §4): Nearby = every open request in my district (any group, so
 * it can be shared), Mine = my requests, My offers = the requests I offered on.
 */
@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val session: SessionManager,
    private val requests: RequestRepository,
    private val offers: OfferRepository,
    private val geoRepository: GeoRepository,
    private val clock: Clock,
    network: NetworkMonitor,
) : ViewModel() {

    private val state = MutableStateFlow(
        RequestsUiState(
            tab = RequestsTabRequest.take()?.also { savedState[TAB_KEY] = it.name }
                ?: savedState.get<String>(TAB_KEY)?.let { t -> RequestsTab.entries.firstOrNull { it.name == t } }
                ?: RequestsTab.NEARBY,
            myUid = (session.state.value as? SessionState.Ready)?.profile?.uid,
        ),
    )
    val uiState: StateFlow<RequestsUiState> = state.asStateFlow()
    val online: StateFlow<Boolean> = network.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val me: UserProfile? get() = (session.state.value as? SessionState.Ready)?.profile
    private val jobs = mutableMapOf<RequestsTab, Job>()

    /** The last raw item of each list's newest page: where the next page starts. */
    private val cursors = mutableMapOf<RequestsTab, Any>()

    init {
        viewModelScope.launch { state.update { it.copy(geo = geoRepository.geo()) } }
        load(state.value.tab, fresh = true)
    }

    /** D21: the shown screen opens a tab asked for since it was made ([RequestsTabRequest]). */
    fun takeAskedTab() {
        RequestsTabRequest.take()?.let(::selectTab)
    }

    fun selectTab(tab: RequestsTab) {
        savedState[TAB_KEY] = tab.name
        state.update { it.copy(tab = tab) }
        if (!state.value.list(tab).loaded) load(tab, fresh = true)
    }

    fun refresh() = load(state.value.tab, fresh = true, pulled = true)

    fun loadMore() {
        val tab = state.value.tab
        val l = state.value.list(tab)
        if (l.loading || l.endReached || !l.loaded) return
        load(tab, fresh = false)
    }

    /** A fetched page: what to show, how many came back, and the cursor for the next page. */
    private class Page<T>(val shown: List<T>, val rawCount: Int, val cursor: Any?)

    private fun load(tab: RequestsTab, fresh: Boolean, pulled: Boolean = false) {
        val p = me ?: return
        if (jobs[tab]?.isActive == true) {
            if (!fresh) return
            jobs[tab]?.cancel()
        }
        val after = if (fresh) null else cursors[tab]
        jobs[tab] = viewModelScope.launch {
            when (tab) {
                RequestsTab.NEARBY -> run(tab, fresh, pulled, { it.nearby }, { s, l -> s.copy(nearby = l) }) {
                    when (val got = requests.nearby(p.area.district, after as BloodRequest?)) {
                        is AppResult.Failure -> got
                        is AppResult.Success -> AppResult.Success(Page(got.data.filter { visible(it, p) }, got.data.size, got.data.lastOrNull()))
                    }
                }
                RequestsTab.MINE -> run(tab, fresh, pulled, { it.mine }, { s, l -> s.copy(mine = l) }) {
                    when (val got = requests.mine(p.uid, after as BloodRequest?)) {
                        is AppResult.Failure -> got
                        is AppResult.Success -> AppResult.Success(Page(got.data, got.data.size, got.data.lastOrNull()))
                    }
                }
                RequestsTab.OFFERS -> run(tab, fresh, pulled, { it.offers }, { s, l -> s.copy(offers = l) }) {
                    when (val got = offers.mine(p.uid, after as DonorResponse?)) {
                        is AppResult.Failure -> got
                        is AppResult.Success -> {
                            // One query for the page's requests (whereIn), instead of one read each.
                            val byId = (requests.byIds(got.data.map { it.requestId }) as? AppResult.Success)?.data.orEmpty()
                                .associateBy { it.id }
                            AppResult.Success(Page(got.data.map { OfferItem(it, byId[it.requestId]) }, got.data.size, got.data.lastOrNull()))
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> run(
        tab: RequestsTab,
        fresh: Boolean,
        pulled: Boolean,
        get: (RequestsUiState) -> PagedList<T>,
        set: (RequestsUiState, PagedList<T>) -> RequestsUiState,
        fetch: suspend () -> AppResult<Page<T>>,
    ) {
        state.update { s -> set(s, get(s).copy(loading = true, refreshing = pulled, error = null)) }
        when (val result = fetch()) {
            is AppResult.Failure -> state.update { s ->
                set(s, get(s).copy(loaded = true, loading = false, refreshing = false, error = result.error.messageRes))
            }
            is AppResult.Success -> {
                val page = result.data
                page.cursor?.let { cursors[tab] = it } ?: if (fresh) cursors.remove(tab) else Unit
                state.update { s ->
                    val items = if (fresh) page.shown else get(s).items + page.shown
                    set(s, PagedList(items, loaded = true, endReached = page.rawCount < RequestRepository.PAGE_SIZE))
                }
            }
        }
    }

    private fun visible(r: BloodRequest, p: UserProfile): Boolean =
        r.isActive(clock.now()) && r.id !in p.hiddenRequestIds && r.requesterUid !in p.blockedUids

    private companion object {
        const val TAB_KEY = "requests_tab"
    }
}
