package io.github.liquidlyf.core.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this phone knows about a new request's first donor alert (PERF-01, STATE-03). The request
 * page opens as soon as the request is saved; the alert goes out in the background and its result
 * shows there. A topic send gives no count of donors (D6 → v1.1), only the area.
 */
sealed interface AlertStatus {
    /** The Worker is being asked to alert donors. */
    data object Sending : AlertStatus

    /** Alerted. [tier] 1 = the request's upazila, 3 = its district; both the exact blood group. */
    data class Sent(val tier: Int?) : AlertStatus

    /** Over the daily cap: posted, but nobody was alerted. */
    data object DailyLimit : AlertStatus

    /** The call failed; the Worker's 5-minute sweep sends the first alert instead. */
    data object Failed : AlertStatus
}

/**
 * The app's own view of a first alert it just asked for, in memory. The request page reads the
 * request's fan-out fields too, so it stays true after a restart (D's re-audit).
 */
@Singleton
class AlertStatusStore @Inject constructor() {
    private val statuses = MutableStateFlow<Map<String, AlertStatus>>(emptyMap())

    fun observe(requestId: String): Flow<AlertStatus?> = statuses.map { it[requestId] }.distinctUntilChanged()

    fun set(requestId: String, status: AlertStatus) = statuses.update { it + (requestId to status) }
}
