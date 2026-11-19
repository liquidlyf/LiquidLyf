package io.github.liquidlyf.notifications

import android.util.Log
import io.github.liquidlyf.core.data.profile.VerifiedDonationRecorder
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.push.LocalAlert
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app does with a push from the Worker (phase-05 5.2, doc 10 §6). The FCM service only
 * hands the data over, so phone tests can feed the same payloads in.
 * - Request alerts (topics): dropped for my own requests, blocked requesters and after 2 alerts
 *   per request; the second one says "Still needed". Each shown alert is kept for the inbox.
 * - Personal pushes (`usr_{uid}`): always shown; DONATION_RECORDED also records the verified
 *   donation, so the rest period starts even if the donor never opens the request (A15 gap).
 *   One with an inbox item also lights Home's bell (D20).
 * Signed out: nothing is shown.
 */
@Singleton
class PushHandler @Inject constructor(
    private val session: SessionManager,
    private val store: AlertStore,
    private val notifier: AlertNotifier,
    private val requests: RequestRepository,
    private val donations: VerifiedDonationRecorder,
    private val push: PushTopicManager,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Returns the background work it started (the donation record), for tests to wait on. */
    suspend fun handle(data: Map<String, String>): Job? {
        val message = PushMessage.parse(data) ?: return null
        val ready = readySession()
        val uid = ready?.user?.uid
        when (message) {
            is PushMessage.AdminReports -> {
                // The topic is admins-only, but a phone that just lost the claim may still get one.
                if (ready?.admin == true) notifier.showAdminReports(message.count)
            }
            is PushMessage.RequestAlert -> {
                val profile = ready?.profile
                if (profile != null && eligibility.listingBlock(profile, clock.now()) != null) {
                    // D23: past 60 (or weight না), a phone that wasn't opened still has the topics. Drop
                    // the alert and sync, which unsubscribes them.
                    Log.i(TAG, "alert ${message.requestId}: can't be listed")
                    scope.launch { push.sync(profile, force = true) }
                    return null
                }
                val blocked = profile?.blockedUids.orEmpty()
                // The push has no requester; look it up only if I blocked someone (1 read).
                val requesterUid = if (blocked.isEmpty()) {
                    null
                } else {
                    withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                        (requests.byIds(listOf(message.requestId)) as? AppResult.Success)?.data?.firstOrNull()?.requesterUid
                    }
                }
                val decision = AlertPolicy.decide(
                    message, uid, store.isMyRequest(message.requestId), requesterUid, blocked, store.shownCount(message.requestId),
                )
                Log.i(TAG, "alert ${message.requestId}: $decision")
                if (decision !is AlertPolicy.Decision.ShowRequest) return null
                notifier.showRequestAlert(message, decision.stillNeeded)
                store.recordShown(
                    LocalAlert(
                        requestId = message.requestId,
                        stillNeeded = decision.stillNeeded,
                        bloodGroup = message.bloodGroup?.code,
                        district = message.district,
                        urgency = message.urgency?.code,
                        receivedAtMillis = clock.now().toEpochMilli(),
                    ),
                )
            }
            is PushMessage.Personal -> {
                if (uid == null) return null
                val blocked = ready?.profile?.blockedUids.orEmpty()
                val requesterUid = if (blocked.isEmpty() || !message.type.fromRequester) {
                    null
                } else {
                    withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                        (requests.byIds(listOf(message.requestId)) as? AppResult.Success)?.data?.firstOrNull()?.requesterUid
                    }
                }
                val decision = AlertPolicy.decide(message, uid, false, requesterUid, blocked, 0)
                // A donation is still my history: it's recorded below even when the note stays quiet.
                if (decision is AlertPolicy.Decision.ShowPersonal) {
                    notifier.showPersonal(message.type, message.requestId)
                    // D20: Home's bell lights up now, not on the next visit; the inbox item is written.
                    if (message.type.inInbox) store.recordPersonal(clock.now().toEpochMilli())
                }
                if (message.type == PersonalPush.DONATION_RECORDED) {
                    return scope.launch {
                        val written = donations.recordIfMissing(uid, message.requestId)
                        if ((written as? AppResult.Success)?.data == true) session.refresh() // stops alerts for the rest period
                    }
                }
            }
        }
        return null
    }

    /** A new FCM registration may have lost the topics: subscribe again. */
    fun resubscribe() {
        scope.launch { readySession()?.let { push.sync(it.profile, force = true) } }
    }

    /** The session, once it's known (a push may start the app's process). Null if signed out. */
    private suspend fun readySession(): SessionState.Ready? =
        withTimeoutOrNull(SESSION_TIMEOUT_MS) { session.state.first { it !is SessionState.Loading } } as? SessionState.Ready

    private companion object {
        const val TAG = "LiquidLyfPush"
        const val SESSION_TIMEOUT_MS = 8_000L
        const val LOOKUP_TIMEOUT_MS = 4_000L
    }
}
