package io.github.liquidlyf.core.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.hospital.HospitalListRefresher
import io.github.liquidlyf.core.data.hospital.HospitalRefresh
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The once-a-day background check (Phase 9.4, plan D14): the hospital list from the website now,
 * and the app's own `update.json` once A32 item 4 is decided. Silent: no notification, no prompt,
 * and the screens never wait for it.
 *
 * It runs from two places, both waiting for any network:
 * - every day (WorkManager's periodic work);
 * - when the app opens, if it has never checked or the last check was over 24 hours ago, so a
 *   fresh install gets the newer list on its first open instead of a day later.
 */
object DailyChecks {
    const val DAILY = "daily_check"
    const val ON_OPEN = "daily_check_on_open"
    const val CHECK_AFTER_HOURS = "check_after_hours"

    /** The app opened: a check only if the last one is a day old. */
    const val ON_OPEN_AFTER_HOURS = 24L

    /** Periodic runs drift by a few hours: one that comes a little early still checks. */
    const val DAILY_AFTER_HOURS = 20L

    /** From Application.onCreate. Both are unique work, so starting the app again adds nothing. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        work.enqueueUniquePeriodicWork(
            DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(network)
                .setInputData(workDataOf(CHECK_AFTER_HOURS to DAILY_AFTER_HOURS))
                .build(),
        )
        work.enqueueUniqueWork(
            ON_OPEN,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyCheckWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(CHECK_AFTER_HOURS to ON_OPEN_AFTER_HOURS))
                .build(),
        )
    }
}

/** Workers aren't built by Hilt here (no extra hilt-work dependency): they reach the graph this way. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DailyCheckEntryPoint {
    fun hospitals(): HospitalListRefresher
}

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, DailyCheckEntryPoint::class.java)
        val after = Duration.ofHours(inputData.getLong(DailyChecks.CHECK_AFTER_HOURS, DailyChecks.ON_OPEN_AFTER_HOURS))
        return when (graph.hospitals().refreshIfDue(after)) {
            // Offline in practice, though the network constraint was met: a couple of retries with
            // WorkManager's backoff, then wait for the next open or the next day.
            HospitalRefresh.Failed -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
