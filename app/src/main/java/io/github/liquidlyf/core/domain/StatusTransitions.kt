package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus

/**
 * Who may move a request or a response to which status (doc 06 §4.2). The same state machines
 * are enforced in firestore.rules (`ownerUpdate`, `reportUpdate`, `adminUpdate`,
 * `donorWithdraw`, `requesterDecision`); the UI uses these to decide which buttons to show.
 * The Worker alone sets EXPIRED. Nothing ever goes back to OPEN except an admin restore.
 */
object StatusTransitions {

    /** Distinct reports after which a reporter's batch may hide (REMOVE) an OPEN request. */
    const val REMOVE_AT_REPORTS = 3

    // ---- Requests ----

    /** The requester can cancel an OPEN request or mark it fulfilled. */
    fun requesterCanSet(from: RequestStatus, to: RequestStatus): Boolean =
        from == RequestStatus.OPEN && (to == RequestStatus.CANCELLED || to == RequestStatus.FULFILLED)

    /** A report batch may hide an OPEN request once it reaches [REMOVE_AT_REPORTS] reports. */
    fun reportsCanRemove(from: RequestStatus, reportCountAfter: Int): Boolean =
        from == RequestStatus.OPEN && reportCountAfter >= REMOVE_AT_REPORTS

    /** An admin (custom claim) may hide, cancel or restore a request. */
    fun adminCanSet(to: RequestStatus): Boolean =
        to == RequestStatus.OPEN || to == RequestStatus.REMOVED || to == RequestStatus.CANCELLED

    /** Status after the requester marks one more unit donated: FULFILLED once all units are in. */
    fun statusAfterDonation(unitsFulfilledAfter: Int, unitsNeeded: Int): RequestStatus =
        if (unitsFulfilledAfter >= unitsNeeded) RequestStatus.FULFILLED else RequestStatus.OPEN

    // ---- Responses (offers) ----

    /** Requester: OFFERED → CONFIRMED or DECLINED; CONFIRMED → DONATED or DECLINED. */
    fun requesterNext(from: ResponseStatus): Set<ResponseStatus> = when (from) {
        ResponseStatus.OFFERED -> setOf(ResponseStatus.CONFIRMED, ResponseStatus.DECLINED)
        ResponseStatus.CONFIRMED -> setOf(ResponseStatus.DONATED, ResponseStatus.DECLINED)
        ResponseStatus.DECLINED, ResponseStatus.WITHDRAWN, ResponseStatus.DONATED -> emptySet()
    }

    /** Donor: OFFERED or CONFIRMED → WITHDRAWN. */
    fun donorNext(from: ResponseStatus): Set<ResponseStatus> = when (from) {
        ResponseStatus.OFFERED, ResponseStatus.CONFIRMED -> setOf(ResponseStatus.WITHDRAWN)
        ResponseStatus.DECLINED, ResponseStatus.WITHDRAWN, ResponseStatus.DONATED -> emptySet()
    }

    fun requesterCanSet(from: ResponseStatus, to: ResponseStatus): Boolean = to in requesterNext(from)

    fun donorCanSet(from: ResponseStatus, to: ResponseStatus): Boolean = to in donorNext(from)
}
