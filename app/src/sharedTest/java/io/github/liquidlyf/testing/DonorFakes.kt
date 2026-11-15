package io.github.liquidlyf.testing

import io.github.liquidlyf.core.data.config.FeatureFlags
import io.github.liquidlyf.core.data.donor.DonorRepository
import io.github.liquidlyf.core.data.donor.DonorSearch
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import java.time.Instant

/** Donor cards in memory, filtered and ordered like the Firestore query. */
class FakeDonorRepository : DonorRepository {
    val cards = mutableListOf<DonorCard>()
    val searches = mutableListOf<DonorSearch>()
    var counts = 0
    var failNext: AppError? = null

    private fun matching(f: DonorSearch, now: Instant) = cards
        .filter { if (f.upazila != null) it.area.upazila == f.upazila else it.area.district == f.district }
        .filter { it.bloodGroup in f.groups && it.available }
        .filter { !f.eligibleNowOnly || !it.eligibleFrom.isAfter(now) }
        .sortedWith(compareBy({ it.eligibleFrom }, { it.uid }))

    override suspend fun search(filters: DonorSearch, now: Instant, after: DonorCard?, limit: Long): AppResult<List<DonorCard>> {
        failNext?.let { failNext = null; return AppResult.Failure(it) }
        searches += filters
        val all = matching(filters, now)
        val start = after?.let { a -> all.indexOfFirst { it.uid == a.uid } + 1 } ?: 0
        return AppResult.Success(all.drop(start).take(limit.toInt()))
    }

    override suspend fun count(filters: DonorSearch, now: Instant): AppResult<Long> {
        counts++
        return AppResult.Success(matching(filters, now).size.toLong())
    }
}

class FakeFeatureFlags(var donorSearch: Boolean = true) : FeatureFlags {
    override suspend fun donorSearchEnabled() = donorSearch
}

fun TestData.donor(
    uid: String,
    group: BloodGroup = BloodGroup.B_POS,
    area: Area = DHAKA_SOUTH,
    eligibleFrom: Instant = Instant.EPOCH,
    available: Boolean = true,
) = DonorCard(
    uid = uid, displayName = "Donor $uid", bloodGroup = group, area = area, available = available,
    eligibleFrom = eligibleFrom, donationCount = 2, verifiedDonations = 1, phoneVerified = false, lastActiveAt = NOW,
)
