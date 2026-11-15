package io.github.liquidlyf.feature.moderation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.moderation.ModerationRepository
import io.github.liquidlyf.core.data.moderation.RequestAction
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Report
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** One open report with what it's about. */
data class ModerationItem(
    val report: Report,
    /** For a REQUEST report; null if it couldn't be read. */
    val request: BloodRequest? = null,
    /** For a USER report: their name (admins can read `users`). */
    val personName: String? = null,
) {
    /** "Asking money" from someone who offered on that request: the strongest signal (7.3). */
    val reporterOffered: Boolean get() = request?.responders?.containsKey(report.reporterUid) == true

    /** Lower comes first: money asked of a donor, then requests that mention money, then the rest. */
    val priority: Int
        get() = when {
            report.reason == ReportReason.ASKING_MONEY && reporterOffered -> 0
            request?.flagged == true -> 1
            else -> 2
        }
}

data class ModerationUiState(
    /** False for anyone without the admin claim: the screen then only says so. */
    val admin: Boolean = false,
    val items: List<ModerationItem>? = null,
    val error: AppError? = null,
    val working: Boolean = false,
)

sealed interface ModerationEvent {
    data class Message(@param:StringRes val text: Int) : ModerationEvent
}

/** Phase 7.5: Settings → Moderation, shown only with the `admin` claim (the rules check it too). */
@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val session: SessionManager,
    private val moderation: ModerationRepository,
    private val requests: RequestRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val state = MutableStateFlow(ModerationUiState())
    val uiState: StateFlow<ModerationUiState> = state.asStateFlow()
    private val events = Channel<ModerationEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val ready: SessionState.Ready? get() = session.state.value as? SessionState.Ready

    init {
        load()
    }

    fun load() {
        val admin = ready?.admin == true
        state.update { it.copy(admin = admin, error = null) }
        if (!admin) return
        viewModelScope.launch {
            when (val result = moderation.openReports()) {
                is AppResult.Failure -> state.update { it.copy(error = result.error) }
                is AppResult.Success -> state.update { it.copy(items = withTargets(result.data)) }
            }
        }
    }

    fun dismiss(item: ModerationItem, note: String?) = run { admin -> moderation.dismiss(admin, item.report, note) }

    fun act(item: ModerationItem, action: RequestAction, note: String?) {
        val request = item.request ?: return
        // Every open report on this request is decided together, not just the one tapped.
        val reports = state.value.items.orEmpty()
            .map { it.report }
            .filter { it.targetType == ReportTarget.REQUEST && it.targetId == request.id }
            .ifEmpty { listOf(item.report) }
        run { admin -> moderation.act(admin, action, request, reports, note) }
    }

    fun markHandled(item: ModerationItem, note: String?) = run { admin -> moderation.markPersonHandled(admin, item.report, note) }

    private fun run(block: suspend (adminUid: String) -> AppResult<Unit>) {
        val admin = ready?.takeIf { it.admin }?.user?.uid ?: return
        if (state.value.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = block(admin)
            state.update { it.copy(working = false) }
            events.send(ModerationEvent.Message(if (result is AppResult.Failure) result.error.messageRes else R.string.moderation_done))
            if (result is AppResult.Success) load()
        }
    }

    /** One read for the requests (by id) and one per reported person, then priority order. */
    private suspend fun withTargets(reports: List<Report>): List<ModerationItem> {
        val requestIds = reports.filter { it.targetType == ReportTarget.REQUEST }.map { it.targetId }.distinct()
        val byId = if (requestIds.isEmpty()) {
            emptyMap()
        } else {
            (requests.byIds(requestIds) as? AppResult.Success)?.data.orEmpty().associateBy { it.id }
        }
        val names = reports.filter { it.targetType == ReportTarget.USER }.map { it.targetId }.distinct()
            .associateWith { uid -> (profiles.profile(uid) as? AppResult.Success)?.data?.fullName }
        return reports.map { r ->
            when (r.targetType) {
                ReportTarget.REQUEST -> ModerationItem(r, request = byId[r.targetId])
                ReportTarget.USER -> ModerationItem(r, personName = names[r.targetId])
            }
        }.sortedWith(compareBy<ModerationItem> { it.priority }.thenByDescending { it.report.createdAt ?: Instant.MAX })
    }
}
