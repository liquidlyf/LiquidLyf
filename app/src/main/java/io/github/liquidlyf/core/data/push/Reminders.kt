package io.github.liquidlyf.core.data.push

import java.time.Instant

/**
 * On-device reminders (phase-05 5.4), so they cost no server work: "You can donate again" at the
 * end of the rest period (it also re-subscribes the alert topics), and "Your request ends soon"
 * 2 hours before a request's needed-by time. WorkManager keeps them across restarts and reboots.
 */
interface Reminders {
    /** Null cancels it. Replaces any earlier one. */
    fun scheduleEligibleAgain(at: Instant?)

    fun scheduleRequestEnding(requestId: String, neededBy: Instant)

    fun cancelRequestEnding(requestId: String)

    /** Sign-out: nothing of this person's may fire. */
    fun cancelAll()
}
