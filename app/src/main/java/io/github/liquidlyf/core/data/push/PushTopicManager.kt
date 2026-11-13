package io.github.liquidlyf.core.data.push

import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.Tiers
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Subscribes this device to FCM topics. The demo flavor uses a no-op (no FCM there). */
interface TopicMessaging {
    suspend fun subscribe(topic: String)

    suspend fun unsubscribe(topic: String)
}

/** The topics this device is subscribed to, as far as the app knows. */
interface SubscribedTopicsStore {
    suspend fun get(): Set<String>

    suspend fun set(topics: Set<String>)
}

/**
 * Keeps this device's FCM topics in line with the profile (doc 10 §6.2):
 * - always `usr_{uid}` while signed in, for personal updates;
 * - the donor's upazila, district and division topics only while listed, available and eligible.
 * Call [sync] on app start (with force), after a profile change, availability toggle or donation,
 * and with null on logout.
 */
@Singleton
class PushTopicManager @Inject constructor(
    private val messaging: TopicMessaging,
    private val store: SubscribedTopicsStore,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
) {

    /** The topics [profile] should be subscribed to right now. Empty when signed out. */
    fun desiredTopics(profile: UserProfile?): Set<String> {
        if (profile == null) return emptySet()
        val topics = linkedSetOf(Tiers.userTopic(profile.uid))
        if (isMatchable(profile)) {
            topics += Tiers.donorTopics(profile.bloodGroup, profile.area.division, profile.area.district, profile.area.upazila)
        }
        return topics
    }

    /**
     * When the desired topics will next change on their own: the end of the rest period. The
     * "eligible again" reminder (Phase 5) syncs again then. Null if nothing is pending.
     */
    fun nextChangeAt(profile: UserProfile?): Instant? {
        if (profile == null || !profile.showInDonorSearch || !profile.available) return null
        return eligibility.eligibleFrom(profile.lastDonationAt, profile.gender).takeIf { it.isAfter(clock.now()) }
    }

    /**
     * Subscribes to what's missing and unsubscribes from what's no longer wanted. [force]
     * re-subscribes every desired topic (FCM may have lost them, e.g. after a token change);
     * subscribing twice is harmless. A failed call leaves the store saying "not done", so the
     * next sync retries it.
     */
    suspend fun sync(profile: UserProfile?, force: Boolean = false): AppResult<Unit> = appResultOf {
        val desired = desiredTopics(profile)
        val current = store.get().toMutableSet()
        try {
            for (topic in current - desired) {
                messaging.unsubscribe(topic)
                current -= topic
            }
            for (topic in if (force) desired else desired - current) {
                messaging.subscribe(topic)
                current += topic
            }
        } finally {
            store.set(current)
        }
    }

    private fun isMatchable(p: UserProfile): Boolean =
        p.showInDonorSearch && p.available &&
            eligibility.evaluate(p.birthYear, p.weightOk, p.gender, p.lastDonationAt, clock.now()).eligibleNow
}
