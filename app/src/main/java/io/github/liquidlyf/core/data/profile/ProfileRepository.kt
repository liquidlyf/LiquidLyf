package io.github.liquidlyf.core.data.profile

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.WriteBatch
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.DonationDoc
import io.github.liquidlyf.core.firebase.dto.DonorDoc
import io.github.liquidlyf.core.firebase.dto.UserDoc
import io.github.liquidlyf.core.firebase.dto.UserStatsDoc
import io.github.liquidlyf.core.firebase.dto.donations
import io.github.liquidlyf.core.firebase.dto.donorRef
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.firebase.dto.userRef
import io.github.liquidlyf.core.firebase.getCacheFirst
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.model.UserStats
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.map
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The private profile, its public donor card and the donation history. Every write is one batch
 * from the write contract in firebase/README.md (items 1–5, 12, 13): the card always equals the
 * profile after the batch, and the counters move only with the donation doc that justifies them.
 */
interface ProfileRepository {
    /** The profile, from the cache when possible. Null if profile setup isn't done yet. */
    suspend fun profile(uid: String, refresh: Boolean = false): AppResult<UserProfile?>

    suspend fun stats(uid: String, refresh: Boolean = false): AppResult<UserStats?>

    /** Profile setup (contract 1): users, userStats and, if listed, the donor card. */
    suspend fun create(uid: String, email: String, draft: ProfileDraft): AppResult<Unit>

    /** Profile edit, listing and unlisting (contracts 2, 3). */
    suspend fun update(before: UserProfile, draft: ProfileDraft): AppResult<Unit>

    /** At most once every 3 days (contract 4). Success(true) when it wrote, false when not due. */
    suspend fun touchLastActive(profile: UserProfile): AppResult<Boolean>

    /** "Seen" for the whole inbox: one timestamp instead of a flag per item. */
    suspend fun markInboxSeen(profile: UserProfile): AppResult<Unit>

    /**
     * "Not for me" (Phase 4.5): hides a request from Home on every device. The list lives on the
     * user doc (rules: at most 200), so the oldest entries drop off.
     */
    suspend fun hideRequest(profile: UserProfile, requestId: String): AppResult<Unit>

    suspend fun donations(uid: String): AppResult<List<Donation>>

    /** Contract 13: a donation the donor types in. Never verified. */
    suspend fun addSelfDonation(
        profile: UserProfile,
        donatedAt: Instant,
        component: BloodComponent,
        hospitalName: String?,
    ): AppResult<Unit>

    /** Removes a self-reported donation, and its count. Verified ones stay. */
    suspend fun deleteSelfDonation(profile: UserProfile, donation: Donation): AppResult<Unit>

    /**
     * Contract 12: after the requester marked this donor DONATED on [request]. Pass the response's
     * server `updatedAt` as [donatedAt]: the rules reject a time later than the server's clock.
     */
    suspend fun recordVerifiedDonation(profile: UserProfile, request: BloodRequest, donatedAt: Instant): AppResult<Unit>
}

@Singleton
class FirestoreProfileRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
) : ProfileRepository {

    override suspend fun profile(uid: String, refresh: Boolean): AppResult<UserProfile?> = appResultOf {
        val snap = db.userRef(uid).getCacheFirst(refresh)
        snap.data?.let { UserDoc.fromMap(uid, it) }
    }

    override suspend fun stats(uid: String, refresh: Boolean): AppResult<UserStats?> = appResultOf {
        db.statsRef(uid).getCacheFirst(refresh).data?.let(UserStatsDoc::fromMap)
    }

    override suspend fun create(uid: String, email: String, draft: ProfileDraft): AppResult<Unit> {
        if (!isValid(draft)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            val batch = db.batch()
            val profile = UserDoc.createMap(draft, email)
            batch.set(db.userRef(uid), profile)
            batch.set(db.statsRef(uid), UserStatsDoc.createMap())
            if (draft.showInDonorSearch) {
                // The card is built from what the profile will be, with the same server times.
                val after = UserProfile(
                    uid = uid, fullName = Validators.normalizeName(draft.fullName), email = email, phone = draft.phone,
                    phoneVerified = false, bloodGroup = draft.bloodGroup, gender = draft.gender,
                    birthYear = draft.birthYear, weightOk = draft.weightOk, area = draft.area,
                    available = draft.available, lastDonationAt = draft.lastDonationAt, showInDonorSearch = true,
                    consentAt = null, locale = draft.locale, termsVersion = draft.termsVersion, termsAcceptedAt = null,
                    createdAt = null, updatedAt = null, role = null, hiddenRequestIds = emptyList(),
                    blockedUids = emptyList(), inboxSeenAt = null, lastRequestAt = null, lastActiveAt = null,
                    donationCount = 0, verifiedDonations = 0, lastDonationId = null,
                )
                batch.set(db.donorRef(uid), DonorDoc.map(after, eligibleFrom(after), lastActiveAtIsServerTime = true))
            }
            batch.commit().await()
        }
    }

    override suspend fun update(before: UserProfile, draft: ProfileDraft): AppResult<Unit> {
        if (!isValid(draft)) return AppResult.Failure(AppError.InvalidInput)
        val after = UserDoc.applyDraft(before, draft, clock.now())
        val newlyListed = after.showInDonorSearch && !before.showInDonorSearch
        return writeProfile(before, after, if (newlyListed) setOf(UserDoc.CONSENT_AT) else emptySet())
    }

    override suspend fun touchLastActive(profile: UserProfile): AppResult<Boolean> {
        val last = profile.lastActiveAt
        // The rules allow a new value only after 3 days; wait a little longer for clock skew.
        if (last != null && clock.now().isBefore(last.plus(LAST_ACTIVE_EVERY))) return AppResult.Success(false)
        return writeProfile(profile, profile, setOf(UserDoc.LAST_ACTIVE_AT)).map { true }
    }

    override suspend fun markInboxSeen(profile: UserProfile): AppResult<Unit> =
        writeProfile(profile, profile, setOf(UserDoc.INBOX_SEEN_AT))

    override suspend fun hideRequest(profile: UserProfile, requestId: String): AppResult<Unit> {
        if (requestId in profile.hiddenRequestIds) return AppResult.Success(Unit)
        val ids = (profile.hiddenRequestIds + requestId).takeLast(Validators.MAX_HIDDEN_OR_BLOCKED)
        return writeProfile(profile, profile.copy(hiddenRequestIds = ids))
    }

    override suspend fun donations(uid: String): AppResult<List<Donation>> = appResultOf {
        db.donations(uid).orderBy(DonationDoc.DONATED_AT, Query.Direction.DESCENDING).get().await()
            .documents.mapNotNull { d -> d.data?.let { DonationDoc.fromMap(d.id, it) } }
    }

    override suspend fun addSelfDonation(
        profile: UserProfile,
        donatedAt: Instant,
        component: BloodComponent,
        hospitalName: String?,
    ): AppResult<Unit> {
        if (donatedAt.isAfter(clock.now()) || !Validators.fitsOptional(hospitalName, Validators.HOSPITAL_NAME_MAX)) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        val ref = db.donations(profile.uid).document()
        val after = profile.copy(
            donationCount = profile.donationCount + 1,
            lastDonationId = ref.id,
            lastDonationAt = latest(profile.lastDonationAt, donatedAt),
        )
        return writeProfile(profile, after) { batch ->
            batch.set(ref, DonationDoc.selfMap(donatedAt, component, hospitalName))
        }
    }

    override suspend fun deleteSelfDonation(profile: UserProfile, donation: Donation): AppResult<Unit> {
        if (donation.source != DonationSource.SELF) return AppResult.Failure(AppError.PermissionDenied)
        // lastDonationAt stays: the donor can correct it in the profile if this was the latest.
        val after = profile.copy(
            donationCount = (profile.donationCount - 1).coerceAtLeast(0),
            lastDonationId = donation.id,
        )
        return writeProfile(profile, after) { batch ->
            batch.delete(db.donations(profile.uid).document(donation.id))
        }
    }

    override suspend fun recordVerifiedDonation(
        profile: UserProfile,
        request: BloodRequest,
        donatedAt: Instant,
    ): AppResult<Unit> {
        val after = profile.copy(
            donationCount = profile.donationCount + 1,
            verifiedDonations = profile.verifiedDonations + 1,
            lastDonationId = request.id,
            lastDonationAt = latest(profile.lastDonationAt, donatedAt),
        )
        return writeProfile(profile, after) { batch ->
            batch.set(
                db.donations(profile.uid).document(request.id),
                DonationDoc.verifiedMap(request.id, donatedAt, request.component, request.hospitalName),
            )
        }
    }

    /**
     * One batch: [extra] writes, the profile update, and the donor card kept equal to the profile
     * (set while listed, deleted when unlisted, untouched if nothing it mirrors changed).
     */
    private suspend fun writeProfile(
        before: UserProfile,
        after: UserProfile,
        serverTimeFields: Set<String> = emptySet(),
        extra: (WriteBatch) -> Unit = {},
    ): AppResult<Unit> = appResultOf {
        val batch = db.batch()
        extra(batch)
        batch.update(db.userRef(after.uid), UserDoc.updateMap(before, after, serverTimeFields))
        val lastActiveIsServerTime = UserDoc.LAST_ACTIVE_AT in serverTimeFields
        when {
            after.showInDonorSearch && (cardChanged(before, after) || lastActiveIsServerTime) ->
                batch.set(db.donorRef(after.uid), DonorDoc.map(after, eligibleFrom(after), lastActiveIsServerTime))
            !after.showInDonorSearch && before.showInDonorSearch -> batch.delete(db.donorRef(after.uid))
        }
        batch.commit().await()
    }

    private fun cardChanged(before: UserProfile, after: UserProfile): Boolean =
        !before.showInDonorSearch ||
            before.bloodGroup != after.bloodGroup || before.area != after.area || before.available != after.available ||
            before.lastDonationAt != after.lastDonationAt || before.donationCount != after.donationCount ||
            before.verifiedDonations != after.verifiedDonations || before.phoneVerified != after.phoneVerified ||
            before.gender != after.gender || Validators.displayName(before.fullName) != Validators.displayName(after.fullName)

    private fun eligibleFrom(p: UserProfile): Instant = eligibility.eligibleFrom(p.lastDonationAt, p.gender)

    private fun latest(a: Instant?, b: Instant): Instant = if (a == null || b.isAfter(a)) b else a

    private fun isValid(d: ProfileDraft): Boolean =
        Validators.isValidName(d.fullName) && Validators.isBdPhone(d.phone) &&
            Validators.isValidBirthYear(d.birthYear, clock.now()) &&
            Validators.isCode(d.area.division) && Validators.isCode(d.area.district) && Validators.isCode(d.area.upazila) &&
            (d.lastDonationAt == null || !d.lastDonationAt.isAfter(clock.now())) && d.termsVersion.isNotBlank() &&
            // Being listed needs a known blood group (P1; the rules check it too).
            (!d.showInDonorSearch || d.bloodGroup != null)

    private companion object {
        val LAST_ACTIVE_EVERY: Duration = Duration.ofDays(3).plusHours(1)
    }
}
