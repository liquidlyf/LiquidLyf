package io.github.liquidlyf.core.firebase.dto

import io.github.liquidlyf.core.domain.BloodCompatibility
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestLocation
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import java.time.Instant
import java.time.temporal.ChronoUnit

/** `requests/{id}`. Keys match `requestKeys()` in firestore.rules. */
object RequestDoc {
    const val COLLECTION = "requests"

    /** What a deleted account's name becomes on its requests and offers (exact; the rules check it). */
    const val DELETED_NAME = "Deleted user"

    const val REQUESTER_UID = "requesterUid"
    const val REQUESTER_DISPLAY_NAME = "requesterDisplayName"
    const val BLOOD_GROUP = "bloodGroup"
    const val COMPONENT = "component"
    const val ACCEPT_COMPATIBLE = "acceptCompatible"
    const val ACCEPTED_GROUPS = "acceptedGroups"
    const val UNITS_NEEDED = "unitsNeeded"
    const val UNITS_FULFILLED = "unitsFulfilled"
    const val CONDITION = "condition"
    const val HOSPITAL_ID = "hospitalId"
    const val HOSPITAL_NAME = "hospitalName"
    const val HOSPITAL_ADDRESS = "hospitalAddress"
    const val DIVISION = "division"
    const val DISTRICT = "district"
    const val UPAZILA = "upazila"
    const val LOCATION = "location"
    const val URGENCY = "urgency"
    const val NEEDED_BY = "neededBy"
    const val NOTES = "notes"
    const val FLAGGED = "flagged"
    const val STATUS = "status"
    const val CLOSED_REASON = "closedReason"
    const val RESPONSE_COUNT = "responseCount"
    const val REPORT_COUNT = "reportCount"
    const val ASK_COUNT = "askCount"
    const val RESPONDERS = "responders"
    const val FANOUT_TIER = "fanoutTier"
    const val NEXT_TIER_AT = "nextTierAt"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val EXPIRES_AT = "expiresAt"
    const val LAST_RESPONDER_UID = "lastResponderUid"
    const val LAST_ASKED_UID = "lastAskedUid"

    private const val LAT = "lat"
    private const val LNG = "lng"
    private const val GEOHASH = "geohash"

    fun fromMap(id: String, m: Map<String, Any?>): BloodRequest? = BloodRequest(
        id = id,
        requesterUid = m.str(REQUESTER_UID) ?: return null,
        requesterDisplayName = m.str(REQUESTER_DISPLAY_NAME) ?: return null,
        bloodGroup = BloodGroup.fromCode(m.str(BLOOD_GROUP)) ?: return null,
        component = BloodComponent.fromCode(m.str(COMPONENT)) ?: return null,
        acceptCompatible = m.bool(ACCEPT_COMPATIBLE) ?: false,
        acceptedGroups = m.strings(ACCEPTED_GROUPS).mapNotNull(BloodGroup::fromCode),
        unitsNeeded = m.int(UNITS_NEEDED) ?: return null,
        unitsFulfilled = m.int(UNITS_FULFILLED) ?: 0,
        condition = PatientCondition.fromCode(m.str(CONDITION)),
        hospitalId = m.str(HOSPITAL_ID),
        hospitalName = m.str(HOSPITAL_NAME) ?: return null,
        hospitalAddress = m.str(HOSPITAL_ADDRESS),
        division = m.str(DIVISION) ?: return null,
        district = m.str(DISTRICT) ?: return null,
        upazila = m.str(UPAZILA),
        location = m.map(LOCATION)?.let(::locationOf),
        urgency = Urgency.fromCode(m.str(URGENCY)) ?: return null,
        neededBy = m.instant(NEEDED_BY) ?: return null,
        notes = m.str(NOTES),
        flagged = m.bool(FLAGGED) ?: false,
        status = RequestStatus.fromCode(m.str(STATUS)) ?: return null,
        closedReason = m.str(CLOSED_REASON),
        responseCount = m.int(RESPONSE_COUNT) ?: 0,
        reportCount = m.int(REPORT_COUNT) ?: 0,
        askCount = m.int(ASK_COUNT) ?: 0,
        responders = m.map(RESPONDERS).orEmpty().mapNotNull { (uid, value) ->
            @Suppress("UNCHECKED_CAST")
            (value as? Map<String, Any?>)?.let(::responderOf)?.let { uid to it }
        }.toMap(),
        fanoutTier = m.int(FANOUT_TIER) ?: 0,
        nextTierAt = m.instant(NEXT_TIER_AT),
        createdAt = m.instant(CREATED_AT),
        updatedAt = m.instant(UPDATED_AT),
        expiresAt = m.instant(EXPIRES_AT) ?: return null,
    )

    private fun locationOf(m: Map<String, Any?>): RequestLocation? = RequestLocation(
        lat = m.double(LAT) ?: return null,
        lng = m.double(LNG) ?: return null,
        geohash = m.str(GEOHASH) ?: return null,
    )

    fun responderOf(m: Map<String, Any?>): Responder? = Responder(
        name = m.str(Responders.NAME) ?: return null,
        group = BloodGroup.fromCode(m.str(Responders.GROUP)) ?: return null,
        status = ResponseStatus.fromCode(m.str(Responders.STATUS)) ?: return null,
    )

    /** neededBy is stored in whole seconds, and expiresAt is exactly 24 h later (contract 6). */
    fun neededByOf(draft: RequestDraft): Instant = draft.neededBy.truncatedTo(ChronoUnit.SECONDS)

    /**
     * A new request (write contract 6): OPEN, every counter 0, no responders, and the
     * Worker-owned fan-out fields at their fixed start values.
     */
    fun createMap(draft: RequestDraft, requesterUid: String, requesterDisplayName: String): Map<String, Any?> {
        val neededBy = neededByOf(draft)
        val notes = draft.notes?.trim()?.ifEmpty { null }
        return fields(
            REQUESTER_UID to requesterUid,
            REQUESTER_DISPLAY_NAME to requesterDisplayName,
            BLOOD_GROUP to draft.bloodGroup.code,
            COMPONENT to draft.component.code,
            ACCEPT_COMPATIBLE to draft.acceptCompatible,
            ACCEPTED_GROUPS to BloodCompatibility.acceptedGroups(draft.bloodGroup, draft.component, draft.acceptCompatible)
                .map { it.code },
            UNITS_NEEDED to draft.unitsNeeded,
            UNITS_FULFILLED to 0,
            CONDITION to draft.condition?.code,
            HOSPITAL_ID to draft.hospitalId,
            HOSPITAL_NAME to draft.hospitalName.trim(),
            HOSPITAL_ADDRESS to draft.hospitalAddress?.trim()?.ifEmpty { null },
            DIVISION to draft.division,
            DISTRICT to draft.district,
            UPAZILA to draft.upazila,
            LOCATION to draft.location?.let { mapOf(LAT to it.lat, LNG to it.lng, GEOHASH to it.geohash) },
            URGENCY to draft.urgency.code,
            NEEDED_BY to neededBy.toTimestamp(),
            NOTES to notes,
            FLAGGED to Validators.hasMoneyWords(notes),
            STATUS to RequestStatus.OPEN.code,
            RESPONSE_COUNT to 0,
            REPORT_COUNT to 0,
            ASK_COUNT to 0,
            RESPONDERS to emptyMap<String, Any?>(),
            FANOUT_TIER to 0,
            NEXT_TIER_AT to serverTime(),
            CREATED_AT to serverTime(),
            UPDATED_AT to serverTime(),
            EXPIRES_AT to Validators.expiresAt(neededBy).toTimestamp(),
        )
    }
}

/** Keys of one `requests.responders` entry. No phone. */
object Responders {
    const val NAME = "name"
    const val GROUP = "group"
    const val STATUS = "status"

    fun entry(r: Responder): Map<String, Any?> = mapOf(NAME to r.name, GROUP to r.group.code, STATUS to r.status.code)

    /** Dotted field path for one donor's entry, for `update()`. */
    fun path(uid: String): String = "${RequestDoc.RESPONDERS}.$uid"
}

/** `requests/{rid}/private/contact`. Keys match `validContact` in firestore.rules. */
object ContactDoc {
    const val COLLECTION = "private"
    const val DOC_ID = "contact"

    const val REQUESTER_UID = "requesterUid"
    const val CONTACT_NAME = "contactName"
    const val CONTACT_PHONE = "contactPhone"
    const val PATIENT_NAME = "patientName"
    const val WARD_OR_BED = "wardOrBed"

    fun fromMap(m: Map<String, Any?>): RequestContact? = RequestContact(
        requesterUid = m.str(REQUESTER_UID) ?: return null,
        contactName = m.str(CONTACT_NAME) ?: return null,
        contactPhone = m.str(CONTACT_PHONE) ?: return null,
        patientName = m.str(PATIENT_NAME),
        wardOrBed = m.str(WARD_OR_BED),
    )

    fun createMap(draft: RequestDraft, requesterUid: String): Map<String, Any?> = fields(
        REQUESTER_UID to requesterUid,
        CONTACT_NAME to Validators.normalizeName(draft.contactName),
        CONTACT_PHONE to draft.contactPhone,
        PATIENT_NAME to draft.patientName?.trim()?.ifEmpty { null },
        WARD_OR_BED to draft.wardOrBed?.trim()?.ifEmpty { null },
    )
}

/** `requests/{rid}/responses/{donorUid}`. Keys match `validOffer` in firestore.rules. */
object ResponseDoc {
    const val COLLECTION = "responses"

    const val DONOR_UID = "donorUid"
    const val REQUEST_ID = "requestId"
    const val REQUESTER_UID = "requesterUid"
    const val STATUS = "status"
    const val DONOR_DISPLAY_NAME = "donorDisplayName"
    const val DONOR_BLOOD_GROUP = "donorBloodGroup"
    const val DONOR_PHONE = "donorPhone"
    const val MESSAGE = "message"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"

    fun fromMap(m: Map<String, Any?>): DonorResponse? = DonorResponse(
        donorUid = m.str(DONOR_UID) ?: return null,
        requestId = m.str(REQUEST_ID) ?: return null,
        requesterUid = m.str(REQUESTER_UID) ?: return null,
        status = ResponseStatus.fromCode(m.str(STATUS)) ?: return null,
        donorDisplayName = m.str(DONOR_DISPLAY_NAME) ?: return null,
        donorBloodGroup = BloodGroup.fromCode(m.str(DONOR_BLOOD_GROUP)) ?: return null,
        donorPhone = m.str(DONOR_PHONE),
        message = m.str(MESSAGE),
        createdAt = m.instant(CREATED_AT),
        updatedAt = m.instant(UPDATED_AT),
    )

    /** A donor's offer (write contract 9). Sharing the phone here is the donor's consent. */
    fun offerMap(
        requestId: String,
        requesterUid: String,
        donorUid: String,
        donorDisplayName: String,
        donorBloodGroup: BloodGroup,
        donorPhone: String,
        message: String?,
    ): Map<String, Any?> = fields(
        DONOR_UID to donorUid,
        REQUEST_ID to requestId,
        REQUESTER_UID to requesterUid,
        STATUS to ResponseStatus.OFFERED.code,
        DONOR_DISPLAY_NAME to donorDisplayName,
        DONOR_BLOOD_GROUP to donorBloodGroup.code,
        DONOR_PHONE to donorPhone,
        MESSAGE to message?.trim()?.ifEmpty { null },
        CREATED_AT to serverTime(),
        UPDATED_AT to serverTime(),
    )

    /** A status change by either side (contracts 10, 11): only status and updatedAt. */
    fun statusMap(status: ResponseStatus): Map<String, Any?> =
        mapOf(STATUS to status.code, UPDATED_AT to serverTime())
}
