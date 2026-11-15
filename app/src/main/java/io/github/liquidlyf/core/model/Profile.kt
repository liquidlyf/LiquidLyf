package io.github.liquidlyf.core.model

import java.time.Instant

/**
 * The private profile, `users/{uid}` (doc 07 §2 + §9.1). Owner and admin only. Phone, email,
 * birth year and gender never leave this document.
 *
 * Server-written timestamps are null while a write is still pending in the local cache.
 */
data class UserProfile(
    val uid: String,
    val fullName: String,
    val email: String,
    val phone: String,
    val phoneVerified: Boolean,
    /** Null while the user doesn't know it (P1): they can't be listed or offer until they add it. */
    val bloodGroup: BloodGroup?,
    val gender: Gender,
    val birthYear: Int,
    val weightOk: Boolean,
    val area: Area,
    val available: Boolean,
    val lastDonationAt: Instant?,
    val showInDonorSearch: Boolean,
    val consentAt: Instant?,
    val locale: AppLocale,
    val termsVersion: String,
    val termsAcceptedAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val role: String?,
    val hiddenRequestIds: List<String>,
    val blockedUids: List<String>,
    val inboxSeenAt: Instant?,
    val lastRequestAt: Instant?,
    val lastActiveAt: Instant?,
    val donationCount: Int,
    val verifiedDonations: Int,
    val lastDonationId: String?,
    /**
     * Phase 6 "Rapid responder" badge: the Worker sets it once an offer of mine came within 15
     * minutes of its request. Read-only for the app (the rules refuse any change), sticky once set.
     */
    val rapidResponder: Boolean = false,
)

/** Division, district and upazila or city-corporation thana codes from the bundled geo data. */
data class Area(
    val division: String,
    val district: String,
    val upazila: String,
)

/** Language used for push text and the app (doc 07 §2). */
enum class AppLocale(val code: String) {
    EN("en"),
    BN("bn"),
    ;

    companion object {
        fun fromCode(code: String?): AppLocale? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What profile setup and profile edit collect. The repository adds the server-owned fields
 * (timestamps, counters) and derives the public donor card from it.
 */
data class ProfileDraft(
    val fullName: String,
    val phone: String,
    /** Null while the user doesn't know it (P1): they can't be listed or offer until they add it. */
    val bloodGroup: BloodGroup?,
    val gender: Gender,
    val birthYear: Int,
    val weightOk: Boolean,
    val area: Area,
    val available: Boolean,
    val lastDonationAt: Instant?,
    val showInDonorSearch: Boolean,
    val locale: AppLocale,
    val termsVersion: String,
)

/**
 * The public donor card, `donors/{uid}`. Readable by every verified user, so it holds no
 * phone, email, birth year or gender. The owner writes it in the same batch as the profile.
 */
data class DonorCard(
    val uid: String,
    val displayName: String,
    val bloodGroup: BloodGroup,
    val area: Area,
    val available: Boolean,
    /** Never null: [java.time.Instant.EPOCH] means "eligible now". */
    val eligibleFrom: Instant,
    val donationCount: Int,
    val verifiedDonations: Int,
    val phoneVerified: Boolean,
    val lastActiveAt: Instant?,
)

/** `userStats/{uid}`: the open-request counter that the rules use for the 1/3 limit. */
data class UserStats(
    val openRequests: Int,
    val totalRequests: Int,
    val lastRequestId: String?,
)
