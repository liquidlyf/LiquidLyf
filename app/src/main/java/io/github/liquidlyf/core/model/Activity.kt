package io.github.liquidlyf.core.model

import java.time.Instant

/** `users/{uid}/donations/{id}`: the donor's own history. */
data class Donation(
    val id: String,
    val donatedAt: Instant,
    val component: BloodComponent,
    val hospitalName: String?,
    /** Set for [DonationSource.REQUEST] entries; the doc id is then the request id. */
    val requestId: String?,
    val verified: Boolean,
    val source: DonationSource,
    val createdAt: Instant?,
)

enum class DonationSource(val code: String) {
    /** Recorded through a request the requester marked DONATED. Verified. */
    REQUEST("REQUEST"),

    /** Typed in by the donor. Not verified. */
    SELF("SELF"),
    ;

    companion object {
        fun fromCode(code: String?): DonationSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * `users/{uid}/notifications/{id}`: a personal inbox entry. No free text: the app renders the
 * localized message from [type] and [requestId], so nobody can write a scam into another inbox.
 * Immutable; "seen" is `users.inboxSeenAt`.
 */
data class InboxItem(
    val id: String,
    val type: InboxType,
    val fromUid: String,
    val requestId: String,
    val createdAt: Instant?,
)

enum class InboxType(val code: String) {
    /** Donor → requester: someone offered to donate. */
    NEW_OFFER("NEW_OFFER"),
    OFFER_CONFIRMED("OFFER_CONFIRMED"),
    OFFER_DECLINED("OFFER_DECLINED"),
    DONATION_RECORDED("DONATION_RECORDED"),

    /** Requester → listed donor: "Can you donate?" */
    ASKED_TO_DONATE("ASKED_TO_DONATE"),
    ;

    companion object {
        fun fromCode(code: String?): InboxType? = entries.firstOrNull { it.code == code }
    }
}

enum class ReportTarget(val code: String) {
    REQUEST("REQUEST"),
    USER("USER"),
    ;

    companion object {
        fun fromCode(code: String?): ReportTarget? = entries.firstOrNull { it.code == code }
    }
}
