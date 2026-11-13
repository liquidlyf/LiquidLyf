package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup

/** A request's matching fields, as the Worker reads them (doc 10 §6.3). */
data class TierRequest(
    val bloodGroup: BloodGroup,
    val component: BloodComponent,
    val acceptCompatible: Boolean,
    val acceptedGroups: List<BloodGroup>,
    val division: String,
    val district: String,
    val upazila: String?,
)

/**
 * FCM topic names and tiered fan-out (doc 06 §4.3, doc 10 §6).
 *
 * Mirrors backend/worker/src/tiers.ts line for line; both are tested against
 * backend/shared/tier-vectors.json. The device uses [donorTopics] and [userTopic] to subscribe
 * (PushTopicManager); the Worker uses the tier functions to send.
 *
 * Tier 1: exact group, upazila. Tier 2: the other accepted groups, upazila (only with
 * acceptCompatible). Tier 3: exact group, district. Tier 4: the accepted groups in each
 * neighbouring district plus the other accepted groups in the own district; Rh-negative uses the
 * whole division instead. Rh-negative requests and requests without an upazila start at tier 3.
 */
object Tiers {

    /** FCM allows at most 5 topics in one condition. */
    const val MAX_TOPICS_PER_CONDITION = 5
    const val LAST_TIER = 4

    enum class Level(val prefix: String) { UPAZILA("up"), DISTRICT("di"), DIVISION("dv") }

    /** `{up|di|dv}_{areaCode}_{GROUP}`. Throws if [area] isn't a safe code. */
    fun topic(level: Level, area: String, group: BloodGroup): String {
        require(Validators.isCode(area)) { "bad ${level.prefix} code" }
        return "${level.prefix}_${area}_${group.code}"
    }

    /** `usr_{uid}`: each signed-in device's personal updates. */
    fun userTopic(uid: String): String {
        require(Validators.isId(uid)) { "bad uid" }
        return "usr_$uid"
    }

    /**
     * The area topics a donor subscribes to while listed, available and eligible (doc 10 §6.2):
     * their upazila/thana, district and division, for their own blood group.
     */
    fun donorTopics(group: BloodGroup, division: String, district: String, upazila: String): Set<String> =
        linkedSetOf(
            topic(Level.UPAZILA, upazila, group),
            topic(Level.DISTRICT, district, group),
            topic(Level.DIVISION, division, group),
        )

    /** Accepted groups, limited to the compatibility table, in [BloodGroup.entries] order. */
    private fun accepted(r: TierRequest): List<BloodGroup> {
        if (!r.acceptCompatible) return listOf(r.bloodGroup)
        val allowed = BloodCompatibility.compatibleDonors(r.bloodGroup, r.component).toSet()
        return BloodGroup.entries.filter { it == r.bloodGroup || (it in r.acceptedGroups && it in allowed) }
    }

    private fun others(r: TierRequest): List<BloodGroup> = accepted(r).filter { it != r.bloodGroup }

    /** The topics one tier sends to. Empty means "nothing to send at this tier". */
    fun tierTopics(r: TierRequest, tier: Int, neighbours: List<String> = emptyList()): List<String> {
        val upazila = r.upazila?.takeIf { it.isNotEmpty() }
        val rhNegative = r.bloodGroup.isRhNegative
        return when (tier) {
            1 -> if (!rhNegative && upazila != null) listOf(topic(Level.UPAZILA, upazila, r.bloodGroup)) else emptyList()
            2 -> if (!rhNegative && upazila != null) others(r).map { topic(Level.UPAZILA, upazila, it) } else emptyList()
            3 -> listOf(topic(Level.DISTRICT, r.district, r.bloodGroup))
            4 -> if (rhNegative) {
                accepted(r).map { topic(Level.DIVISION, r.division, it) }
            } else {
                others(r).map { topic(Level.DISTRICT, r.district, it) } +
                    neighbours.filter { it != r.district }
                        .flatMap { d -> accepted(r).map { topic(Level.DISTRICT, d, it) } }
            }
            else -> emptyList()
        }
    }

    /** FCM conditions, at most [MAX_TOPICS_PER_CONDITION] topics each. */
    fun toConditions(topics: List<String>): List<String> =
        topics.chunked(MAX_TOPICS_PER_CONDITION).map { chunk -> chunk.joinToString(" || ") { "'$it' in topics" } }

    fun tierConditions(r: TierRequest, tier: Int, neighbours: List<String> = emptyList()): List<String> =
        toConditions(tierTopics(r, tier, neighbours))

    /** The next tier after [after] that sends something, or null when fan-out is finished. */
    fun nextTier(r: TierRequest, after: Int, neighbours: List<String> = emptyList()): Int? =
        ((after + 1)..LAST_TIER).firstOrNull { tierTopics(r, it, neighbours).isNotEmpty() }

    /** The first tier to send: 1, or 3 for Rh-negative requests and requests without an upazila. */
    fun firstTier(r: TierRequest, neighbours: List<String> = emptyList()): Int? = nextTier(r, 0, neighbours)
}
