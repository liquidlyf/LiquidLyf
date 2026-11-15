package io.github.liquidlyf.notifications

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.profile.FirestoreProfileRepository
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.request.FirestoreOfferRepository
import io.github.liquidlyf.core.data.request.FirestoreRequestRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Phase 5.2 / 5.11 on the phone and the Firebase emulators: the Worker's exact payloads go
 * through the app's push handling (the demo flavor has no FCM). Request alerts show at most twice
 * ("Still needed" the second time), never for my own request, and the tap opens the request.
 * DONATION_RECORDED records the verified donation without opening the request (the A15 gap).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PushHandlerTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var offers: OfferRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var handler: PushHandler
    @Inject lateinit var store: AlertStore
    @Inject lateinit var eligibility: EligibilityCalculator
    @Inject lateinit var clock: Clock
    @Inject lateinit var settings: io.github.liquidlyf.core.data.settings.SettingsRepository

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
        manager.cancelAll()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() }
        }
    }

    @Test
    fun alertsShowAtMostTwiceNeverForMyOwnAndTheTapOpensTheRequest() = runBlocking {
        val (requesterProfiles, requesterRequests, _) = requester("push-a")
        val rid = requesterRequests.create(requesterProfiles.first, requestDraft()).ok()
        donor("push-b")

        val alert = mapOf(
            "type" to "NEW_REQUEST", "requestId" to rid, "bloodGroup" to "O_POS",
            "district" to "BD3026", "urgency" to "URGENT", "tier" to "1",
        )
        handler.handle(alert)
        val first = waitForNotification(rid)
        assertEquals(str(R.string.notification_new_request_title, str(R.string.blood_group_o_pos)), first.title())
        assertTrue("Body names the urgency and district: ${first.text()}", first.text().startsWith("${str(R.string.urgency_urgent)} · Dhaka"))

        handler.handle(alert + ("tier" to "3"))
        waitUntil("Still needed") { notificationFor(rid)?.title() == str(R.string.notification_still_needed_title, str(R.string.blood_group_o_pos)) }
        handler.handle(alert + ("tier" to "4"))
        assertEquals("No third alert", 2, store.shownCount(rid))
        assertEquals(2, store.alerts.first().count { it.requestId == rid })

        // My own request's alert reaches my phone too (I'm a matching donor), and stays silent.
        store.rememberMyRequest("my-own-request")
        handler.handle(alert + ("requestId" to "my-own-request"))
        assertNull(notificationFor("my-own-request"))

        // Tapping the notification opens the request (a fresh task, through the deep link).
        notificationFor(rid)!!.notification.contentIntent.send()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(str(R.string.title_request_detail)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Dhaka Medical College Hospital").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anAskToDonatePopsUpOnTheUrgentChannelAndUpdatesStayQuiet() = runBlocking {
        val (requesterSide, requesterRequests, _) = requester("push-ask")
        val rid = requesterRequests.create(requesterSide.first, requestDraft()).ok()
        donor("push-ask-d")

        handler.handle(mapOf("type" to "ASKED_TO_DONATE", "requestId" to rid))
        val ask = waitForNotification(rid)
        assertEquals(str(R.string.notification_asked_title), ask.title())
        assertEquals("A18: an ask pops up like an area alert", NotificationChannels.URGENT_REQUESTS, ask.notification.channelId)

        handler.handle(mapOf("type" to "OFFER_CONFIRMED", "requestId" to rid))
        waitUntil("the confirm update") { manager.activeNotifications.any { it.id == "$rid:OFFER_CONFIRMED".hashCode() } }
        val update = manager.activeNotifications.first { it.id == "$rid:OFFER_CONFIRMED".hashCode() }
        assertEquals(NotificationChannels.REQUEST_UPDATES, update.notification.channelId)
    }

    @Test
    fun newReportsShowOnlyOnAnAdminsPhoneAndTheTapOpensModeration() = runBlocking {
        val uid = donor("push-admin")
        val reports = mapOf("type" to "NEW_REPORTS", "count" to "2")
        handler.handle(reports)
        delay(1_000)
        assertNull("Not an admin: nothing shown", manager.activeNotifications.firstOrNull { it.id == ADMIN_REPORTS_NOTIFICATION_ID })

        // The claim, as setAdmin.mjs sets it; the app sees it with the next token.
        EmulatorAccounts.setClaims(uid, """{"admin":true}""")
        auth.currentUser!!.getIdToken(true).await()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid && it.admin } }
        handler.handle(reports)
        waitUntil("the new-reports notification") { manager.activeNotifications.any { it.id == ADMIN_REPORTS_NOTIFICATION_ID } }
        val shown = manager.activeNotifications.first { it.id == ADMIN_REPORTS_NOTIFICATION_ID }
        assertEquals(str(R.string.notification_reports_title), shown.title())
        assertEquals(str(R.string.notification_reports_body, "2"), shown.text())
        assertEquals(NotificationChannels.REQUEST_UPDATES, shown.notification.channelId)

        // The tap opens Moderation (a fresh task, through its deep link), even when the app
        // would otherwise reopen another tab (A19 on prod: Home was the last tab, and won).
        settings.setLastSelectedTab(io.github.liquidlyf.navigation.TopLevelDestination.HOME.name)
        shown.notification.contentIntent.send()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(str(R.string.title_moderation)).fetchSemanticsNodes().isNotEmpty() }
        // The admin's view, not "Only admins can see this".
        compose.waitUntil(20_000) { compose.onAllNodesWithText(str(R.string.moderation_admins_only)).fetchSemanticsNodes().isEmpty() }
        auth.signOut()
    }

    @Test
    fun donationRecordedRecordsTheDonationWithoutOpeningTheRequest() = runBlocking {
        val (requesterSide, requesterRequests, requesterOffers) = requester("push-c")
        val (aProfile, aUid) = requesterSide
        val rid = requesterRequests.create(aProfile, requestDraft()).ok()
        val bUid = donor("push-d")

        // B offers in the app; A confirms and marks it donated on their own phone.
        offers.offer(requests.byIds(listOf(rid)).ok().single(), profiles.profile(bUid, refresh = true).ok()!!, null).ok()
        val response = withTimeout(20_000) { requesterOffers.responders(rid, aUid).first { it.isNotEmpty() } }.single()
        requesterOffers.decide(requesterRequests.byIds(listOf(rid)).ok().single(), response, ResponseStatus.CONFIRMED).ok()
        val confirmed = withTimeout(20_000) { requesterOffers.responders(rid, aUid).first { it.single().status == ResponseStatus.CONFIRMED } }.single()
        requesterOffers.decide(requesterRequests.byIds(listOf(rid)).ok().single(), confirmed, ResponseStatus.DONATED).ok()

        // The push arrives; B never opens the request.
        handler.handle(mapOf("type" to "DONATION_RECORDED", "requestId" to rid))?.join()
        assertEquals(str(R.string.notification_donation_recorded_title), waitForNotification(rid).title())
        val history = profiles.donations(bUid).ok()
        assertTrue("Verified donation recorded", history.any { it.id == rid && it.verified })
        // Once only: a repeated push writes nothing more.
        handler.handle(mapOf("type" to "DONATION_RECORDED", "requestId" to rid))?.join()
        assertEquals(1, profiles.donations(bUid).ok().count { it.id == rid })
    }

    // ---- people ----

    /** The requester, on a second Firebase client, so the app's session can be the donor's. */
    private suspend fun requester(tag: String): Triple<Pair<io.github.liquidlyf.core.model.UserProfile, String>, RequestRepository, OfferRepository> {
        val (auth2, db2) = EmulatorAccounts.secondFirebase("push-requester")
        val email = EmulatorAccounts.newVerifiedUser(auth2, tag)
        val uid = auth2.uid!!
        val repo = FirestoreProfileRepository(db2, eligibility, clock)
        repo.create(uid, email, profileDraft(BloodGroup.A_POS, listed = false, phone = "+8801812345678")).ok()
        val profile = repo.profile(uid, refresh = true).ok()!!
        return Triple(profile to uid, FirestoreRequestRepository(db2, clock), FirestoreOfferRepository(db2, clock))
    }

    /** The donor is the app's own signed-in user. */
    private suspend fun donor(tag: String): String {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(BloodGroup.O_POS, listed = true, phone = "+8801712345678")).ok()
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
        return uid
    }

    // ---- notifications ----

    private fun notificationFor(requestId: String): StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.notification.contentIntent != null && it.id == "$requestId:alert".hashCode() }
            ?: manager.activeNotifications.firstOrNull { sbn -> PersonalPush.entries.any { sbn.id == "$requestId:${it.code}".hashCode() } }

    private suspend fun waitForNotification(requestId: String): StatusBarNotification {
        waitUntil("notification for $requestId") { notificationFor(requestId) != null }
        return notificationFor(requestId)!!
    }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun StatusBarNotification.text() = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    // ---- helpers ----

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

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

    private fun profileDraft(group: BloodGroup, listed: Boolean, phone: String) = ProfileDraft(
        fullName = "Test Person", phone = phone, bloodGroup = group, gender = Gender.FEMALE, birthYear = 1995,
        weightOk = true, area = dhakaSouth, available = true, lastDonationAt = null, showInDonorSearch = listed,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital", hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(6, ChronoUnit.HOURS), notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )
}
