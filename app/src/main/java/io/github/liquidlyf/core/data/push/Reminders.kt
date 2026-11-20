package io.github.liquidlyf.core.data.push

import java.time.Instant

/**
 * On-device reminders (phase-05 5.4), so they cost no server work: "You can donate again" at the
 * end of the rest period (it also re-subscribes the alert topics), and "Your request ends soon"
 * 2 hours before a request's needed-by time, and "Did <donor> donate?" an hour after it (FLOW-10). WorkManager keeps them across restarts and reboots.
 */
interface Reminders {
    /** Null cancels it. Replaces any earlier one. */
    fun scheduleEligibleAgain(at: Instant?)

    fun scheduleRequestEnding(requestId: String, neededBy: Instant)

    fun cancelRequestEnding(requestId: String)

    /**
     * FLOW-10 (D5 a): an hour after [neededBy] (or after now, if that's later), "Did <donor> donate?"
     * when a confirmed donor hasn't been marked yet. Replaces any earlier one for the request.
     */
    fun scheduleDidDonate(requestId: String, neededBy: Instant)

    fun cancelDidDonate(requestId: String)

    /**
     * D29: "Want to donate too?" at each of [slots] (the ones not set yet); empty cancels them all.
     * Each one checks again when it's due.
     */
    fun scheduleDonorReminders(slots: List<Instant>)

    /** Sign-out: nothing of this person's may fire, and the donor reminder's switch is on again. */
    fun cancelAll()
}
