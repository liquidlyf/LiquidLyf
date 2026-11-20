package io.github.liquidlyf.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import io.github.liquidlyf.core.data.push.AlertPrefs
import io.github.liquidlyf.core.data.push.AlertPrefsStore
import io.github.liquidlyf.core.data.push.Reminders
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.profilesetup.SetupDraft
import io.github.liquidlyf.feature.profilesetup.SetupDraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/** The one "Want to donate too?" reminder: a newer one replaces an unread older one. */
internal const val DONOR_REMINDER_NOTIFICATION_ID = 3

/**
 * D29, the maintainer's idea: a few reminders to become a donor, made on this phone: no server
 * sends them, and nothing new is written. When one is due, the phone reads only the person's own
 * data (the profile, and their userStats for an open request). Who gets them and when is worked
 * out from the account's creation (setup), so nothing is counted or kept: Fridays at 10:00 phone
 * time (the weekend here, and before Jumu'ah), the first one 7 or more days after setup, weekly
 * for 4 weeks, then every 4 weeks for 3 more, then never: 7 in all. A Friday is skipped, not
 * moved, while their own request is open.
 */
class DonorReminderPlan @Inject constructor(private val eligibility: EligibilityCalculator) {

    /** All 7 times for an account set up at [setupDoneAt]. */
    fun slots(setupDoneAt: Instant, zone: ZoneId): List<Instant> {
        val earliest = setupDoneAt.plus(FIRST_AFTER).atZone(zone)
        var first = earliest.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).atTime(AT).atZone(zone)
        if (first.isBefore(earliest)) first = first.plusWeeks(1)
        return WEEKS.map { first.plusWeeks(it).toInstant() }
    }

    /**
     * Only for someone who isn't a donor and could be one: not listed, never finished Become a
     * donor ("Not now" at "list me" was their choice too), and no answer kept on this phone (D28)
     * that rules donating out (weight না, or a year of birth outside 18–60). The switch is on.
     */
    fun wanted(profile: UserProfile, kept: SetupDraft?, prefs: AlertPrefs, now: Instant): Boolean {
        if (!prefs.donorReminder) return false
        if (profile.hasDonorDetails || profile.showInDonorSearch) return false
        if (kept?.weightOk == false) return false
        // Only a whole year counts: "19" is a year still being typed.
        val year = kept?.birthYear?.trim()?.takeIf { it.length == 4 }?.toIntOrNull()
        return year == null || eligibility.listingBlock(year, true, now) == null
    }

    /** The times still to come for [profile], or none. */
    fun upcoming(profile: UserProfile, kept: SetupDraft?, prefs: AlertPrefs, now: Instant, zone: ZoneId): List<Instant> {
        if (!wanted(profile, kept, prefs, now)) return emptyList()
        val setupDone = profile.createdAt ?: return emptyList()
        return slots(setupDone, zone).filter { it.isAfter(now) }
    }

    companion object {
        val FIRST_AFTER: Duration = Duration.ofDays(7)
        val AT: LocalTime = LocalTime.of(10, 0)
        /** Weeks after the first: 4 weekly, then 3 about a month apart. */
        val WEEKS = listOf(0L, 1L, 2L, 3L, 7L, 11L, 15L)
        /** A reminder that runs this late (the phone was off) is dropped; the next comes on its own. */
        val STALE: Duration = Duration.ofDays(2)
    }
}

/**
 * Keeps the reminders in step with the person: scheduled while they're wanted, all cancelled once
 * they aren't (they became a donor, turned the switch off, or signed out). Started with the app.
 */
@Singleton
class DonorReminderScheduler @Inject constructor(
    private val session: SessionManager,
    private val prefs: AlertPrefsStore,
    private val drafts: SetupDraftStore,
    private val reminders: Reminders,
    private val plan: DonorReminderPlan,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { combine(session.state, prefs.prefs, ::Pair).collectLatest { (s, p) -> update(s, p) } }
    }

    internal suspend fun update(state: SessionState, prefs: AlertPrefs, zone: ZoneId = ZoneId.systemDefault()) {
        when (state) {
            is SessionState.Ready -> {
                val profile = state.profile
                reminders.scheduleDonorReminders(plan.upcoming(profile, drafts.load(profile.uid), prefs, clock.now(), zone))
            }
            SessionState.SignedOut -> reminders.scheduleDonorReminders(emptyList())
            else -> Unit // loading, or a state that settles first
        }
    }
}

/** One reminder time: checks everything again, then shows "Want to donate too?" or skips it. */
class DonorReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val now = graph.clock().now()
        val slot = Instant.ofEpochMilli(inputData.getLong(SLOT, 0))
        if (Duration.between(slot, now) > DonorReminderPlan.STALE) return Result.success()
        val ready = readySession(graph.session()) ?: return Result.success() // signed out
        val profile = ready.profile
        if (!graph.donorPlan().wanted(profile, graph.drafts().load(profile.uid), graph.alertPrefs().current(), now)) {
            return Result.success()
        }
        // D's rule: not while their own request is open. That Friday is skipped; the next comes as planned.
        val open = when (val stats = graph.profiles().stats(profile.uid, refresh = true)) {
            is AppResult.Success -> (stats.data?.openRequests ?: 0) > 0
            is AppResult.Failure -> return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        if (!open) graph.notifier().showDonorReminder()
        return Result.success()
    }

    companion object {
        const val SLOT = "slot"
    }
}

/** "Don't remind me" on the notification: the switch goes off, and no more reminders come. */
class DonorReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
        NotificationManagerCompat.from(context).cancel(DONOR_REMINDER_NOTIFICATION_ID)
        graph.reminders().scheduleDonorReminders(emptyList())
        val pending = goAsync()
        graph.appScope().launch {
            try {
                val prefs = graph.alertPrefs()
                prefs.set(prefs.current().copy(donorReminder = false))
            } finally {
                pending.finish()
            }
        }
    }
}
