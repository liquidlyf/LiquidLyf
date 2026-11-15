package io.github.liquidlyf.core.data.donor

import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.DonorDoc
import io.github.liquidlyf.core.firebase.dto.toTimestamp
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Find donors' filters (6.1). [upazila] narrows the [district] when set. */
data class DonorSearch(
    val groups: Set<BloodGroup>,
    val district: String,
    val upazila: String?,
    val eligibleNowOnly: Boolean,
)

/**
 * Listed donors' public cards (`donors/{uid}`): name, group, area, eligibility, counts. Never a
 * phone number. One `get()` per page and one `count()` for the header, no listener (read budget,
 * doc 10 §5.1). Queries match the composite indexes (district|upazila, bloodGroup, available,
 * eligibleFrom) in firebase/firestore.indexes.json.
 */
interface DonorRepository {
    /** Soonest eligible first; [after] is the last card of the previous page. */
    suspend fun search(filters: DonorSearch, now: Instant, after: DonorCard? = null, limit: Long = PAGE_SIZE): AppResult<List<DonorCard>>

    /** How many cards match (1 read per 1000). */
    suspend fun count(filters: DonorSearch, now: Instant): AppResult<Long>

    companion object {
        const val PAGE_SIZE = 20L
    }
}

@Singleton
class FirestoreDonorRepository @Inject constructor(private val db: FirebaseFirestore) : DonorRepository {

    override suspend fun search(filters: DonorSearch, now: Instant, after: DonorCard?, limit: Long): AppResult<List<DonorCard>> =
        appResultOf {
            // Many cards share eligibleFrom (EPOCH = "eligible now"), so the cursor needs the id too.
            var q = query(filters, now).orderBy(FieldPath.documentId())
            after?.let { q = q.startAfter(it.eligibleFrom.toTimestamp(), it.uid) }
            q.limit(limit).get().await().documents.mapNotNull { d -> d.data?.let { DonorDoc.fromMap(d.id, it) } }
        }

    override suspend fun count(filters: DonorSearch, now: Instant): AppResult<Long> = appResultOf {
        query(filters, now).count().get(AggregateSource.SERVER).await().count
    }

    private fun query(f: DonorSearch, now: Instant): Query {
        require(f.groups.isNotEmpty()) { "pick at least one blood group" }
        val area = if (f.upazila != null) {
            db.collection(DonorDoc.COLLECTION).whereEqualTo(DonorDoc.UPAZILA, f.upazila)
        } else {
            db.collection(DonorDoc.COLLECTION).whereEqualTo(DonorDoc.DISTRICT, f.district)
        }
        var q = area
            .whereIn(DonorDoc.BLOOD_GROUP, f.groups.map { it.code })
            .whereEqualTo(DonorDoc.AVAILABLE, true)
        if (f.eligibleNowOnly) q = q.whereLessThanOrEqualTo(DonorDoc.ELIGIBLE_FROM, now.toTimestamp())
        return q.orderBy(DonorDoc.ELIGIBLE_FROM)
    }
}
