package io.github.liquidlyf.feature.donors

import io.github.liquidlyf.core.model.BloodRequest
import java.util.concurrent.atomic.AtomicReference

/**
 * FLOW-06 part 1: a search handed to Find donors from the requester's own request ("Ask donors near
 * this hospital"): the groups that can give to it, in its upazila. Find donors takes it once, when it
 * opens or comes back into view, and searches straight away.
 */
object DonorSearchHandoff {
    private val pending = AtomicReference<DonorFilters?>(null)

    fun hand(request: BloodRequest) {
        pending.set(
            DonorFilters(
                groups = request.acceptedGroups.toSet(),
                division = request.division,
                district = request.district,
                upazila = request.upazila,
            ),
        )
    }

    fun take(): DonorFilters? = pending.getAndSet(null)
}
