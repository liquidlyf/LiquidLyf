package io.github.liquidlyf.core.firebase.dto

import com.google.firebase.firestore.FieldValue
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.model.UserStats
import java.time.Instant

/** `users/{uid}`, the private profile. Keys match `userKeys()` in firestore.rules. */
object UserDoc {
    const val COLLECTION = "users"

    const val FULL_NAME = "fullName"
    const val EMAIL = "email"
    const val PHONE = "phone"
    const val PHONE_VERIFIED = "phoneVerified"
    const val BLOOD_GROUP = "bloodGroup"
    const val GENDER = "gender"
    const val BIRTH_YEAR = "birthYear"
    const val WEIGHT_OK = "weightOk"
    const val DIVISION = "division"
    const val DISTRICT = "district"
    const val UPAZILA = "upazila"
    const val AVAILABLE = "available"
    const val LAST_DONATION_AT = "lastDonationAt"
    const val SHOW_IN_DONOR_SEARCH = "showInDonorSearch"
    const val CONSENT_AT = "consentAt"
    const val LOCALE = "locale"
    const val TERMS_VERSION = "termsVersion"
    const val TERMS_ACCEPTED_AT = "termsAcceptedAt"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val ROLE = "role"
    const val HIDDEN_REQUEST_IDS = "hiddenRequestIds"
    const val BLOCKED_UIDS = "blockedUids"
    const val INBOX_SEEN_AT = "inboxSeenAt"
    const val LAST_REQUEST_AT = "lastRequestAt"
    const val LAST_ACTIVE_AT = "lastActiveAt"
    const val DONATION_COUNT = "donationCount"
    const val VERIFIED_DONATIONS = "verifiedDonations"
    const val LAST_DONATION_ID = "lastDonationId"
    /** Written by the Worker only (B's e798bc7); never in [editable], so profile writes leave it alone. */
    const val RAPID_RESPONDER = "rapidResponder"

    fun fromMap(uid: String, m: Map<String, Any?>): UserProfile? = UserProfile(
        uid = uid,
        fullName = m.str(FULL_NAME) ?: return null,
        email = m.str(EMAIL) ?: return null,
        phone = m.str(PHONE) ?: return null,
        phoneVerified = m.bool(PHONE_VERIFIED) ?: false,
        bloodGroup = BloodGroup.fromCode(m.str(BLOOD_GROUP)),
        gender = Gender.fromCode(m.str(GENDER)) ?: return null,
        birthYear = m.int(BIRTH_YEAR) ?: return null,
        weightOk = m.bool(WEIGHT_OK) ?: return null,
        area = Area(
            division = m.str(DIVISION) ?: return null,
            district = m.str(DISTRICT) ?: return null,
            upazila = m.str(UPAZILA) ?: return null,
        ),
        available = m.bool(AVAILABLE) ?: return null,
        lastDonationAt = m.instant(LAST_DONATION_AT),
        showInDonorSearch = m.bool(SHOW_IN_DONOR_SEARCH) ?: return null,
        consentAt = m.instant(CONSENT_AT),
        locale = AppLocale.fromCode(m.str(LOCALE)) ?: AppLocale.EN,
        termsVersion = m.str(TERMS_VERSION) ?: return null,
        termsAcceptedAt = m.instant(TERMS_ACCEPTED_AT),
        createdAt = m.instant(CREATED_AT),
        updatedAt = m.instant(UPDATED_AT),
        role = m.str(ROLE),
        hiddenRequestIds = m.strings(HIDDEN_REQUEST_IDS),
        blockedUids = m.strings(BLOCKED_UIDS),
        inboxSeenAt = m.instant(INBOX_SEEN_AT),
        lastRequestAt = m.instant(LAST_REQUEST_AT),
        lastActiveAt = m.instant(LAST_ACTIVE_AT),
        donationCount = m.int(DONATION_COUNT) ?: 0,
        verifiedDonations = m.int(VERIFIED_DONATIONS) ?: 0,
        lastDonationId = m.str(LAST_DONATION_ID),
        rapidResponder = m.bool(RAPID_RESPONDER) ?: false,
    )

    /**
     * Profile setup (write contract 1). Counters start at 0, email is the auth email, and there
     * is no phoneVerified or role. Being listed records consentAt (PDPA opt-in).
     */
    fun createMap(draft: ProfileDraft, email: String): Map<String, Any?> = fields(
        FULL_NAME to Validators.normalizeName(draft.fullName),
        EMAIL to email,
        PHONE to draft.phone,
        BLOOD_GROUP to draft.bloodGroup?.code,
        GENDER to draft.gender.code,
        BIRTH_YEAR to draft.birthYear,
        WEIGHT_OK to draft.weightOk,
        DIVISION to draft.area.division,
        DISTRICT to draft.area.district,
        UPAZILA to draft.area.upazila,
        AVAILABLE to draft.available,
        LAST_DONATION_AT to draft.lastDonationAt?.toTimestamp(),
        SHOW_IN_DONOR_SEARCH to draft.showInDonorSearch,
        CONSENT_AT to if (draft.showInDonorSearch) serverTime() else null,
        LOCALE to draft.locale.code,
        TERMS_VERSION to draft.termsVersion,
        TERMS_ACCEPTED_AT to serverTime(),
        CREATED_AT to serverTime(),
        UPDATED_AT to serverTime(),
        LAST_ACTIVE_AT to serverTime(),
        DONATION_COUNT to 0,
        VERIFIED_DONATIONS to 0,
    )

    /** The stored value of every field the owner may change, null meaning "absent". */
    private fun editable(p: UserProfile): Map<String, Any?> = mapOf(
        FULL_NAME to p.fullName,
        PHONE to p.phone,
        PHONE_VERIFIED to p.phoneVerified,
        BLOOD_GROUP to p.bloodGroup?.code,
        GENDER to p.gender.code,
        BIRTH_YEAR to p.birthYear,
        WEIGHT_OK to p.weightOk,
        DIVISION to p.area.division,
        DISTRICT to p.area.district,
        UPAZILA to p.area.upazila,
        AVAILABLE to p.available,
        LAST_DONATION_AT to p.lastDonationAt?.toTimestamp(),
        SHOW_IN_DONOR_SEARCH to p.showInDonorSearch,
        CONSENT_AT to p.consentAt?.toTimestamp(),
        LOCALE to p.locale.code,
        TERMS_VERSION to p.termsVersion,
        TERMS_ACCEPTED_AT to p.termsAcceptedAt?.toTimestamp(),
        HIDDEN_REQUEST_IDS to p.hiddenRequestIds,
        BLOCKED_UIDS to p.blockedUids,
        INBOX_SEEN_AT to p.inboxSeenAt?.toTimestamp(),
        LAST_REQUEST_AT to p.lastRequestAt?.toTimestamp(),
        LAST_ACTIVE_AT to p.lastActiveAt?.toTimestamp(),
        DONATION_COUNT to p.donationCount,
        VERIFIED_DONATIONS to p.verifiedDonations,
        LAST_DONATION_ID to p.lastDonationId,
    )

    /**
     * An update with only the fields that differ between [before] and [after], plus updatedAt.
     * Fields in [serverTimeFields] are written as the server time instead (the rules require
     * `lastActiveAt == request.time`). createdAt, email and role never change.
     */
    fun updateMap(
        before: UserProfile,
        after: UserProfile,
        serverTimeFields: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val old = editable(before)
        val new = editable(after)
        return buildMap {
            for ((key, value) in new) {
                when {
                    key in serverTimeFields -> put(key, serverTime())
                    value != old[key] -> put(key, value ?: FieldValue.delete())
                }
            }
            put(UPDATED_AT, serverTime())
        }
    }

    /** Applies a profile edit. A new phone number loses its verified badge (rules: phoneVerifiedOk). */
    fun applyDraft(before: UserProfile, draft: ProfileDraft, now: Instant): UserProfile = before.copy(
        fullName = Validators.normalizeName(draft.fullName),
        phone = draft.phone,
        phoneVerified = before.phoneVerified && before.phone == draft.phone,
        bloodGroup = draft.bloodGroup,
        gender = draft.gender,
        birthYear = draft.birthYear,
        weightOk = draft.weightOk,
        area = draft.area,
        available = draft.available,
        lastDonationAt = draft.lastDonationAt,
        showInDonorSearch = draft.showInDonorSearch,
        consentAt = if (draft.showInDonorSearch && !before.showInDonorSearch) now else before.consentAt,
        locale = draft.locale,
        termsVersion = draft.termsVersion,
    )
}

/**
 * `donors/{uid}`, the public card. firestore.rules (`donorMatches`) requires it to equal the
 * profile after the batch, so it is always built from the profile.
 */
object DonorDoc {
    const val COLLECTION = "donors"

    const val DISPLAY_NAME = "displayName"
    const val BLOOD_GROUP = "bloodGroup"
    const val DIVISION = "division"
    const val DISTRICT = "district"
    const val UPAZILA = "upazila"
    const val AVAILABLE = "available"
    const val ELIGIBLE_FROM = "eligibleFrom"
    const val DONATION_COUNT = "donationCount"
    const val VERIFIED_DONATIONS = "verifiedDonations"
    const val PHONE_VERIFIED = "phoneVerified"
    const val LAST_ACTIVE_AT = "lastActiveAt"

    fun fromMap(uid: String, m: Map<String, Any?>): DonorCard? = DonorCard(
        uid = uid,
        displayName = m.str(DISPLAY_NAME) ?: return null,
        bloodGroup = BloodGroup.fromCode(m.str(BLOOD_GROUP)) ?: return null,
        area = Area(
            division = m.str(DIVISION) ?: return null,
            district = m.str(DISTRICT) ?: return null,
            upazila = m.str(UPAZILA) ?: return null,
        ),
        available = m.bool(AVAILABLE) ?: return null,
        eligibleFrom = m.instant(ELIGIBLE_FROM) ?: Instant.EPOCH,
        donationCount = m.int(DONATION_COUNT) ?: 0,
        verifiedDonations = m.int(VERIFIED_DONATIONS) ?: 0,
        phoneVerified = m.bool(PHONE_VERIFIED) ?: false,
        lastActiveAt = m.instant(LAST_ACTIVE_AT),
    )

    /**
     * The card for [profile]. [lastActiveAtIsServerTime] must be true when the same batch writes
     * the profile's lastActiveAt as the server time, so both resolve to the same value.
     */
    fun map(profile: UserProfile, eligibleFrom: Instant, lastActiveAtIsServerTime: Boolean): Map<String, Any?> = fields(
        DISPLAY_NAME to Validators.displayName(profile.fullName),
        // Only listed donors have a card, and listing needs a known group (rules + ProfileRepository).
        BLOOD_GROUP to requireNotNull(profile.bloodGroup) { "a donor card needs a blood group" }.code,
        DIVISION to profile.area.division,
        DISTRICT to profile.area.district,
        UPAZILA to profile.area.upazila,
        AVAILABLE to profile.available,
        ELIGIBLE_FROM to eligibleFrom.toTimestamp(),
        DONATION_COUNT to profile.donationCount,
        VERIFIED_DONATIONS to profile.verifiedDonations,
        PHONE_VERIFIED to profile.phoneVerified,
        LAST_ACTIVE_AT to if (lastActiveAtIsServerTime) serverTime() else profile.lastActiveAt?.toTimestamp(),
    )
}

/** `userStats/{uid}`: must exist before the first request (created in profile setup). */
object UserStatsDoc {
    const val COLLECTION = "userStats"

    const val OPEN_REQUESTS = "openRequests"
    const val TOTAL_REQUESTS = "totalRequests"
    const val LAST_REQUEST_ID = "lastRequestId"

    fun fromMap(m: Map<String, Any?>): UserStats = UserStats(
        openRequests = m.int(OPEN_REQUESTS) ?: 0,
        totalRequests = m.int(TOTAL_REQUESTS) ?: 0,
        lastRequestId = m.str(LAST_REQUEST_ID),
    )

    fun createMap(): Map<String, Any?> = mapOf(OPEN_REQUESTS to 0, TOTAL_REQUESTS to 0)
}
