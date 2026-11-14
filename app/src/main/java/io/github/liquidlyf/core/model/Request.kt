package io.github.liquidlyf.core.model

import java.time.Instant

/** A public blood request, `requests/{id}` (doc 07 §2 + §9.1). Readable by verified users. */
data class BloodRequest(
    val id: String,
    val requesterUid: String,
    val requesterDisplayName: String,
    val bloodGroup: BloodGroup,
    val component: BloodComponent,
    val acceptCompatible: Boolean,
    val acceptedGroups: List<BloodGroup>,
    val unitsNeeded: Int,
    val unitsFulfilled: Int,
    val condition: PatientCondition?,
    val hospitalId: String?,
    val hospitalName: String,
    val hospitalAddress: String?,
    val division: String,
    val district: String,
    val upazila: String?,
    val location: RequestLocation?,
    val urgency: Urgency,
    val neededBy: Instant,
    val notes: String?,
    val flagged: Boolean,
    val status: RequestStatus,
    val closedReason: String?,
    val responseCount: Int,
    val reportCount: Int,
    val askCount: Int,
    /** Donor uid → a phone-free summary of their offer. */
    val responders: Map<String, Responder>,
    val fanoutTier: Int,
    val nextTierAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant,
) {
    /** What the feed shows: open and not past its expiry (doc 10 §5.2). */
    fun isActive(now: Instant): Boolean = status == RequestStatus.OPEN && now.isBefore(expiresAt)

    val unitsStillNeeded: Int get() = (unitsNeeded - unitsFulfilled).coerceAtLeast(0)

    /** Offers still standing. `responseCount` only ever grows, so it also counts withdrawn and declined ones. */
    val liveOffers: Int
        get() = responders.values.count { it.status == ResponseStatus.OFFERED || it.status == ResponseStatus.CONFIRMED || it.status == ResponseStatus.DONATED }
}

/** One entry of `requests.responders`. No phone: that lives only in the response doc. */
data class Responder(
    val name: String,
    val group: BloodGroup,
    val status: ResponseStatus,
)

/** Hospital coordinates, for "Open in Maps" (no API key needed). */
data class RequestLocation(
    val lat: Double,
    val lng: Double,
    val geohash: String,
)

/**
 * `requests/{id}/private/contact`: readable only by the requester and by donors who offered.
 * [requesterUid] is denormalized so the read rule needs no billed get().
 */
data class RequestContact(
    val requesterUid: String,
    val contactName: String,
    val contactPhone: String,
    val patientName: String?,
    val wardOrBed: String?,
)

/** What the create-request flow collects. The repository adds the rest. */
data class RequestDraft(
    val bloodGroup: BloodGroup,
    val component: BloodComponent,
    val acceptCompatible: Boolean,
    val unitsNeeded: Int,
    val condition: PatientCondition?,
    val hospitalId: String?,
    val hospitalName: String,
    val hospitalAddress: String?,
    val division: String,
    val district: String,
    val upazila: String?,
    val location: RequestLocation?,
    val urgency: Urgency,
    val neededBy: Instant,
    val notes: String?,
    val contactName: String,
    val contactPhone: String,
    val patientName: String?,
    val wardOrBed: String?,
)

/**
 * A donor's offer, `requests/{rid}/responses/{donorUid}`. [donorPhone] is here only because
 * the donor chose to offer: that is the consent point (doc 07 §2). It is null after the donor
 * deleted their account (the name then reads "Deleted user").
 */
data class DonorResponse(
    val donorUid: String,
    val requestId: String,
    val requesterUid: String,
    val status: ResponseStatus,
    val donorDisplayName: String,
    val donorBloodGroup: BloodGroup,
    val donorPhone: String?,
    val message: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
