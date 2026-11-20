package io.github.liquidlyf.notifications

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.AlertPrefs
import io.github.liquidlyf.core.data.push.AlertPrefsStore
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * D29 on the phone and the Firebase emulators: a due "Want to donate too?" reminder (the real
 * worker, run now instead of on a Friday) shows for someone who only asks for blood, its tap opens
 * Become a donor, and "Don't remind me" turns them off. None while their own request is open, and
 * none for a donor. The schedule itself (which Fridays) is DonorReminderTest's.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DonorReminderPhoneTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var prefs: AlertPrefsStore

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val work by lazy { WorkManager.getInstance(context) }
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
        assumeTrue("Notifications are off for the app on this phone", manager.areNotificationsEnabled())
        manager.cancelAll()
        runBlocking { prefs.set(AlertPrefs()) }
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() }
        }
        if (::prefs.isInitialized) runBlocking { prefs.set(AlertPrefs()) }
    }

    @Test
    fun aDueReminderShowsItsTapOpensBecomeADonorAndDontRemindMeStopsThem() = runBlocking {
        signedIn("donrem-ask", donor = false)
        runReminderNow()
        val shown = waitForReminder()
        assertEquals(context.getString(R.string.home_become_donor_title), shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.notification_donor_reminder_body), shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val dontRemind = shown.actions.single()
        assertEquals(context.getString(R.string.action_dont_remind_me), dontRemind.title.toString())

        // The tap: Become a donor's first question.
        shown.contentIntent.send()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(context.getString(R.string.setup_blood_title)).fetchSemanticsNodes().isNotEmpty() }

        // "Don't remind me": the switch goes off and the notification goes.
        runReminderNow()
        waitForReminder().actions.single().actionIntent.send()
        waitUntil("the switch off") { !prefs.current().donorReminder }
        waitUntil("the notification gone") { reminder() == null }
        // And none after that.
        assertNoReminderAfterRun()
    }

    @Test
    fun noReminderWhileTheirOwnRequestIsOpen() = runBlocking {
        val uid = signedIn("donrem-open", donor = false)
        val me = profiles.profile(uid, refresh = true).ok()!!
        requests.create(me, requestDraft()).ok()
        assertNoReminderAfterRun()
    }

    @Test
    fun aDonorGetsNone() = runBlocking {
        signedIn("donrem-donor", donor = true)
        assertNoReminderAfterRun()
    }

    /** The real worker, due now. */
    private fun runReminderNow(): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<DonorReminderWorker>()
            .setInputData(workDataOf(DonorReminderWorker.SLOT to Instant.now().toEpochMilli()))
            .build()
        work.enqueue(request)
        return request.id
    }

    private suspend fun assertNoReminderAfterRun() {
        val id = runReminderNow()
        waitUntil("the reminder's run") { work.getWorkInfoById(id).get()?.state == WorkInfo.State.SUCCEEDED }
        delay(500)
        assertNull("No reminder expected", reminder())
    }

    private fun reminder(): Notification? = manager.activeNotifications.firstOrNull { it.id == DONOR_REMINDER_NOTIFICATION_ID }?.notification

    private suspend fun waitForReminder(): Notification {
        waitUntil("the reminder") { reminder() != null }
        return reminder()!!
    }

    private suspend fun signedIn(tag: String, donor: Boolean): String {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(donor)).ok()
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
        return uid
    }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(200) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun profileDraft(donor: Boolean) = ProfileDraft(
        fullName = "Test Person", phone = "+8801712345678",
        bloodGroup = if (donor) BloodGroup.O_POS else null,
        gender = if (donor) Gender.FEMALE else null, birthYear = if (donor) 1995 else null, weightOk = if (donor) true else null,
        area = dhakaSouth, available = donor, lastDonationAt = null, showInDonorSearch = donor,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital", hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(Duration.ofHours(6)), notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )
}
