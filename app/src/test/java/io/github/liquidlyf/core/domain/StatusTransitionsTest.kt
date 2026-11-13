package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.RequestStatus.CANCELLED
import io.github.liquidlyf.core.model.RequestStatus.EXPIRED
import io.github.liquidlyf.core.model.RequestStatus.FULFILLED
import io.github.liquidlyf.core.model.RequestStatus.OPEN
import io.github.liquidlyf.core.model.RequestStatus.REMOVED
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.ResponseStatus.CONFIRMED
import io.github.liquidlyf.core.model.ResponseStatus.DECLINED
import io.github.liquidlyf.core.model.ResponseStatus.DONATED
import io.github.liquidlyf.core.model.ResponseStatus.OFFERED
import io.github.liquidlyf.core.model.ResponseStatus.WITHDRAWN
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every (from, to) pair, allowed and not, against the table in firestore.rules. */
class StatusTransitionsTest {

    @Test
    fun `requester closes only an OPEN request, as CANCELLED or FULFILLED`() {
        val allowed = setOf(OPEN to CANCELLED, OPEN to FULFILLED)
        for (from in RequestStatus.entries) for (to in RequestStatus.entries) {
            assertEquals("$from -> $to", (from to to) in allowed, StatusTransitions.requesterCanSet(from, to))
        }
    }

    @Test
    fun `reports hide only an OPEN request, from the third report`() {
        for (from in RequestStatus.entries) {
            for (count in 0..5) {
                assertEquals("$from with $count", from == OPEN && count >= 3, StatusTransitions.reportsCanRemove(from, count))
            }
        }
    }

    @Test
    fun `admin can hide, cancel or restore but never fulfil or expire`() {
        assertEquals(
            setOf(OPEN, REMOVED, CANCELLED),
            RequestStatus.entries.filter { StatusTransitions.adminCanSet(it) }.toSet(),
        )
        assertEquals(false, StatusTransitions.adminCanSet(EXPIRED))
    }

    @Test
    fun `the last donated unit fulfils the request`() {
        assertEquals(OPEN, StatusTransitions.statusAfterDonation(unitsFulfilledAfter = 1, unitsNeeded = 2))
        assertEquals(FULFILLED, StatusTransitions.statusAfterDonation(unitsFulfilledAfter = 2, unitsNeeded = 2))
        assertEquals(FULFILLED, StatusTransitions.statusAfterDonation(unitsFulfilledAfter = 3, unitsNeeded = 2))
    }

    @Test
    fun `requester decides offers - every transition`() {
        val allowed = setOf(
            OFFERED to CONFIRMED, OFFERED to DECLINED,
            CONFIRMED to DONATED, CONFIRMED to DECLINED,
        )
        for (from in ResponseStatus.entries) for (to in ResponseStatus.entries) {
            assertEquals("$from -> $to", (from to to) in allowed, StatusTransitions.requesterCanSet(from, to))
        }
    }

    @Test
    fun `donor can only withdraw an open offer - every transition`() {
        val allowed = setOf(OFFERED to WITHDRAWN, CONFIRMED to WITHDRAWN)
        for (from in ResponseStatus.entries) for (to in ResponseStatus.entries) {
            assertEquals("$from -> $to", (from to to) in allowed, StatusTransitions.donorCanSet(from, to))
        }
    }

    @Test
    fun `final response statuses offer no actions`() {
        for (done in listOf(DECLINED, WITHDRAWN, DONATED)) {
            assertEquals(emptySet<ResponseStatus>(), StatusTransitions.requesterNext(done))
            assertEquals(emptySet<ResponseStatus>(), StatusTransitions.donorNext(done))
        }
    }
}
