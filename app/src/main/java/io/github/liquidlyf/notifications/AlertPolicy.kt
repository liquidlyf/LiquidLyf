package io.github.liquidlyf.notifications

import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Urgency

/** The personal pushes the Worker sends to `usr_{uid}` (backend/worker/src/notify.ts). */
enum class PersonalPush(val code: String) {
    NEW_OFFER("NEW_OFFER"),
    OFFER_CONFIRMED("OFFER_CONFIRMED"),
    OFFER_DECLINED("OFFER_DECLINED"),
    DONATION_RECORDED("DONATION_RECORDED"),
    REQUEST_FULFILLED("REQUEST_FULFILLED"),
    REQUEST_CANCELLED("REQUEST_CANCELLED"),

    /** Phase 6: a requester asks one listed donor. */
    ASKED_TO_DONATE("ASKED_TO_DONATE"),
    ;

    companion object {
        fun fromCode(code: String?): PersonalPush? = entries.firstOrNull { it.code == code }
    }
}

/** A data-only FCM message from the Worker, parsed. Anything else is ignored. */
sealed interface PushMessage {
    val requestId: String

    /** The topic alert (doc 10 §6.3): `{type: NEW_REQUEST, requestId, bloodGroup, district, urgency, tier}`. */
    data class RequestAlert(
        override val requestId: String,
        val bloodGroup: BloodGroup?,
        val district: String?,
        val urgency: Urgency?,
        val tier: Int?,
    ) : PushMessage

    /** `{type, requestId}` only: anyone could subscribe to someone's usr_ topic. */
    data class Personal(val type: PersonalPush, override val requestId: String) : PushMessage

    companion object {
        const val TYPE_NEW_REQUEST = "NEW_REQUEST"

        /** Firestore auto ids and the seed's ids; they also go into a deep-link URI. */
        private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")

        fun parse(data: Map<String, String>): PushMessage? {
            val id = data["requestId"]?.takeIf { ID.matches(it) } ?: return null
            val type = data["type"]
            if (type == TYPE_NEW_REQUEST) {
                return RequestAlert(
                    requestId = id,
                    bloodGroup = BloodGroup.fromCode(data["bloodGroup"]),
                    district = data["district"]?.takeIf { it.isNotBlank() },
                    urgency = Urgency.fromCode(data["urgency"]),
                    tier = data["tier"]?.toIntOrNull(),
                )
            }
            return PersonalPush.fromCode(type)?.let { Personal(it, id) }
        }
    }
}

/**
 * Whether to show a push (phase-05 5.2). Pure, so it's unit-tested. A shown request alert always
 * becomes a visible notification, so FCM keeps the app's high priority.
 */
object AlertPolicy {
    /** The first alert is "New"; one later tier may repeat it as "Still needed". */
    const val MAX_ALERTS_PER_REQUEST = 2

    sealed interface Decision {
        data class Drop(val reason: String) : Decision

        data class ShowRequest(val stillNeeded: Boolean) : Decision

        data object ShowPersonal : Decision
    }

    /**
     * @param myUid the signed-in user, or null when signed out.
     * @param requesterUid the request's owner, when the service had to look it up (only when I
     *   blocked someone; the push itself doesn't carry it).
     */
    fun decide(
        message: PushMessage,
        myUid: String?,
        isMyRequest: Boolean,
        requesterUid: String?,
        blockedUids: Collection<String>,
        shownBefore: Int,
    ): Decision {
        if (myUid == null) return Decision.Drop("signed out")
        return when (message) {
            is PushMessage.Personal -> Decision.ShowPersonal
            is PushMessage.RequestAlert -> when {
                isMyRequest || requesterUid == myUid -> Decision.Drop("my own request")
                requesterUid != null && requesterUid in blockedUids -> Decision.Drop("blocked requester")
                shownBefore >= MAX_ALERTS_PER_REQUEST -> Decision.Drop("already alerted twice")
                else -> Decision.ShowRequest(stillNeeded = shownBefore > 0)
            }
        }
    }
}
