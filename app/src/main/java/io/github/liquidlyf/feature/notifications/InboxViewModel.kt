package io.github.liquidlyf.feature.notifications

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.inbox.InboxRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.profile.VerifiedDonationRecorder
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.push.LocalAlert
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** One line in the inbox: a personal update (Firestore) or a request alert (this phone). */
sealed interface InboxEntry {
    val key: String
    val requestId: String

    /** Null only while a write is still on its way to the server. */
    val at: Instant?

    data class Update(val item: InboxItem) : InboxEntry {
        override val key get() = "u_${item.id}"
        override val requestId get() = item.requestId
        override val at get() = item.createdAt
    }

    data class Alert(val alert: LocalAlert) : InboxEntry {
        override val key get() = "a_${alert.requestId}_${alert.receivedAtMillis}"
        override val requestId get() = alert.requestId
        override val at: Instant get() = Instant.ofEpochMilli(alert.receivedAtMillis)
    }
}

data class InboxUiState(
    /** Null while the first page loads. */
    val entries: List<InboxEntry>? = null,
    /** The last visit: newer entries are marked new. Null if the inbox was never opened. */
    val seenAt: Instant? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    @param:StringRes val error: Int? = null,
    val geo: BdGeo? = null,
) {
    fun isNew(entry: InboxEntry): Boolean = seenAt == null || entry.at?.isAfter(seenAt) ?: true
}

private data class Remote(
    val items: List<InboxItem>? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    @param:StringRes val error: Int? = null,
)

/**
 * The bell (phase-05 5.9): the personal inbox (`users/{uid}/notifications`, paged reads, no
 * listener) merged with the request alerts this phone showed. Opening it writes `inboxSeenAt`
 * once, which clears the badge. A DONATION_RECORDED item records the verified donation if it's
 * still missing (the donor may have missed the push).
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val session: SessionManager,
    private val inbox: InboxRepository,
    alerts: AlertStore,
    private val profiles: ProfileRepository,
    private val donations: VerifiedDonationRecorder,
    geoRepository: GeoRepository,
) : ViewModel() {

    private val me = (session.state.value as? SessionState.Ready)?.profile
    private val remote = MutableStateFlow(Remote())
    private val geo = MutableStateFlow<BdGeo?>(null)
    private var markedSeen = false

    val uiState: StateFlow<InboxUiState> = combine(remote, alerts.alerts, geo) { r, local, g ->
        val items = r.items
        InboxUiState(
            entries = items?.let { merge(it, local) },
            seenAt = me?.inboxSeenAt,
            loadingMore = r.loadingMore,
            endReached = r.endReached,
            error = r.error,
            geo = g,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState(seenAt = me?.inboxSeenAt))

    init {
        viewModelScope.launch { geo.value = geoRepository.geo() }
        loadMore()
    }

    /** The next page of personal updates (the first one on open). */
    fun loadMore() {
        val profile = me ?: return
        val r = remote.value
        if (r.loadingMore || r.endReached) return
        remote.update { it.copy(loadingMore = true, error = null) }
        viewModelScope.launch {
            when (val page = inbox.page(profile.uid, after = r.items?.lastOrNull())) {
                is AppResult.Success -> {
                    remote.update {
                        it.copy(
                            items = it.items.orEmpty() + page.data,
                            loadingMore = false,
                            endReached = page.data.size < InboxRepository.PAGE_SIZE,
                        )
                    }
                    markSeenOnce()
                    recordMissingDonations(page.data)
                }
                is AppResult.Failure -> remote.update { it.copy(loadingMore = false, error = page.error.messageRes) }
            }
        }
    }

    private suspend fun markSeenOnce() {
        val profile = me ?: return
        if (markedSeen) return
        markedSeen = true
        if (profiles.markInboxSeen(profile) is AppResult.Success) session.refresh() // the bell's badge clears
    }

    private suspend fun recordMissingDonations(items: List<InboxItem>) {
        val uid = me?.uid ?: return
        val ids = items.filter { it.type == InboxType.DONATION_RECORDED }.map { it.requestId }.distinct()
        if (ids.isEmpty()) return
        val have = (profiles.donations(uid) as? AppResult.Success)?.data?.map { it.id }?.toSet() ?: return
        var wrote = false
        for (id in ids - have) {
            if ((donations.recordIfMissing(uid, id) as? AppResult.Success)?.data == true) wrote = true
        }
        if (wrote) session.refresh()
    }

    private companion object {
        /** Newest first; an update without a server time yet goes on top. */
        fun merge(items: List<InboxItem>, local: List<LocalAlert>): List<InboxEntry> =
            (items.map { InboxEntry.Update(it) } + local.map { InboxEntry.Alert(it) })
                .sortedByDescending { it.at ?: Instant.MAX }
    }
}
