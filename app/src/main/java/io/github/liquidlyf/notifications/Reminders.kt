package io.github.liquidlyf.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.core.data.push.AlertPrefsStore
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.Reminders
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerReminders @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) : Reminders {
    private val work by lazy { WorkManager.getInstance(context) }

    override fun scheduleEligibleAgain(at: Instant?) {
        if (at == null) {
            work.cancelUniqueWork(ELIGIBLE_AGAIN)
            return
        }
        val request = OneTimeWorkRequestBuilder<EligibleAgainWorker>()
            .setInitialDelay(delayUntil(at), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(ELIGIBLE_AGAIN, ExistingWorkPolicy.REPLACE, request)
    }

    override fun scheduleRequestEnding(requestId: String, neededBy: Instant) {
        val at = neededBy.minus(ENDING_NOTICE)
        if (!at.isAfter(clock.now())) {
            cancelRequestEnding(requestId) // too late for a useful reminder
            return
        }
        val request = OneTimeWorkRequestBuilder<RequestEndingWorker>()
            .setInitialDelay(delayUntil(at), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RequestEndingWorker.REQUEST_ID to requestId))
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(ending(requestId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelRequestEnding(requestId: String) {
        work.cancelUniqueWork(ending(requestId))
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    private fun delayUntil(at: Instant): Long = Duration.between(clock.now(), at).toMillis().coerceAtLeast(0)

    companion object {
        const val TAG = "liquidlyf_reminder"
        const val ELIGIBLE_AGAIN = "eligible_again"
        val ENDING_NOTICE: Duration = Duration.ofHours(2)

        fun ending(requestId: String) = "request_ending_$requestId"
    }
}

/** Workers aren't built by Hilt here (no extra hilt-work dependency): they reach the graph this way. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun session(): SessionManager
    fun notifier(): AlertNotifier
    fun push(): PushTopicManager
    fun requests(): RequestRepository
    fun alertPrefs(): AlertPrefsStore
}

private suspend fun readySession(session: SessionManager): SessionState.Ready? =
    withTimeoutOrNull(10_000) { session.state.first { it !is SessionState.Loading } } as? SessionState.Ready

class EligibleAgainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val ready = readySession(graph.session()) ?: return Result.success() // signed out: nothing to say
        graph.session().refresh()
        // The rest period is over: the alert topics come back (PushTopicManager decides).
        graph.push().sync(ready.profile)
        if (graph.alertPrefs().current().reminders) graph.notifier().showEligibleAgain()
        return Result.success()
    }
}

class RequestEndingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(REQUEST_ID) ?: return Result.success()
        val graph = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val uid = readySession(graph.session())?.user?.uid ?: return Result.success()
        val request = when (val r = graph.requests().byIds(listOf(id))) {
            is AppResult.Success -> r.data.firstOrNull() ?: return Result.success()
            is AppResult.Failure -> return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        // Only my own request, and only while it still waits for blood.
        val wanted = graph.alertPrefs().current().reminders
        if (wanted && request.requesterUid == uid && request.status == RequestStatus.OPEN) graph.notifier().showRequestEnding(id)
        return Result.success()
    }

    companion object {
        const val REQUEST_ID = "requestId"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RemindersModule {
    @Binds abstract fun reminders(impl: WorkManagerReminders): Reminders
}
