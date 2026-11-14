package io.github.liquidlyf.notifications

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.Reminders
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Phase 5.4 on the phone: the real WorkManager runs the reminders, which post their notifications
 * ("You can donate again"; "Your request ends in 2 hours"). Scheduled a few seconds ahead here.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RemindersTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var reminders: Reminders

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
        assumeTrue("Notifications are off for the app on this phone", manager.areNotificationsEnabled())
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        reminders.cancelAll()
        manager.cancelAll()
    }

    @Test
    fun eligibleAgainPostsTheReminder() = runBlocking {
        signedInUser("rem-donor")
        reminders.scheduleEligibleAgain(Instant.now().plusSeconds(2))
        val n = waitForNotification(ELIGIBLE_AGAIN_NOTIFICATION_ID)
        assertEquals(context.getString(R.string.notification_eligible_again_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun requestEndingRemindsTheRequester() = runBlocking {
        val uid = signedInUser("rem-requester")
        val neededBy = Instant.now().plus(Duration.ofHours(2)).plusSeconds(3)
        val rid = (requests.create(profiles.profile(uid, refresh = true).ok()!!, requestDraft(neededBy)) as AppResult.Success).data
        reminders.scheduleRequestEnding(rid, neededBy)
        val n = waitForNotification("$rid:ending".hashCode())
        assertEquals(context.getString(R.string.notification_request_ending_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    private suspend fun signedInUser(tag: String): String {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft()).ok()
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
        delay(1_000) // let the session's own reminder bookkeeping run first
        return uid
    }

    private suspend fun waitForNotification(id: Int): Notification {
        withTimeout(60_000) { while (manager.activeNotifications.none { it.id == id }) delay(300) }
        return manager.activeNotifications.first { it.id == id }.notification
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun profileDraft() = ProfileDraft(
        fullName = "Test Person", phone = "+8801712345678", bloodGroup = BloodGroup.O_POS, gender = Gender.FEMALE, birthYear = 1995,
        weightOk = true, area = dhakaSouth, available = true, lastDonationAt = null, showInDonorSearch = true,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft(neededBy: Instant) = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital", hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = neededBy, notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )
}
