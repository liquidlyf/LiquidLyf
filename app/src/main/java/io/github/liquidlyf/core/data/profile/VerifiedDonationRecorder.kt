package io.github.liquidlyf.core.data.profile

import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract 12: once a requester marks my offer DONATED, my app adds the verified donation to my
 * history, once per request (the doc id is the request id). Runs from the request page, the
 * DONATION_RECORDED push and the inbox, so a donor who never opens the request still gets it
 * (found in A15). The caller refreshes the session when it returns true: eligibility and the
 * alert topics change.
 */
@Singleton
class VerifiedDonationRecorder @Inject constructor(
    private val profiles: ProfileRepository,
    private val offers: OfferRepository,
    private val requests: RequestRepository,
    private val clock: Clock,
) {
    /** True if a new verified donation was written; false if it was already there or isn't due. */
    suspend fun recordIfMissing(uid: String, request: BloodRequest): AppResult<Boolean> {
        if (request.responders[uid]?.status != ResponseStatus.DONATED) return AppResult.Success(false)
        val existing = when (val d = profiles.donations(uid)) {
            is AppResult.Success -> d.data
            is AppResult.Failure -> return d
        }
        if (existing.any { it.id == request.id }) return AppResult.Success(false)
        val fresh = when (val p = profiles.profile(uid, refresh = true)) {
            is AppResult.Success -> p.data ?: return AppResult.Failure(AppError.NotFound)
            is AppResult.Failure -> return p
        }
        val donatedAt = (offers.myOffer(request.id, uid) as? AppResult.Success)?.data?.updatedAt ?: clock.now()
        return when (val r = profiles.recordVerifiedDonation(fresh, request, donatedAt)) {
            is AppResult.Success -> AppResult.Success(true)
            is AppResult.Failure -> r
        }
    }

    /** From a push or an inbox item, which carry only the id (1 read for the request). */
    suspend fun recordIfMissing(uid: String, requestId: String): AppResult<Boolean> {
        val request = when (val r = requests.byIds(listOf(requestId))) {
            is AppResult.Success -> r.data.firstOrNull() ?: return AppResult.Success(false)
            is AppResult.Failure -> return r
        }
        return recordIfMissing(uid, request)
    }
}
