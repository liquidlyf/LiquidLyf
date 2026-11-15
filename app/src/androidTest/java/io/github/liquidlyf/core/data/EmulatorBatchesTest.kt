package io.github.liquidlyf.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.core.data.inbox.InboxRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.firebase.dto.DonorDoc
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.donorRef
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Every repository batch against session B's real firestore.rules, on the Firebase emulators
 * (demo flavor). Needs `npm run emulators` in firebase/ on the PC and, on a USB phone,
 * `adb reverse tcp:8085 tcp:8085` and `adb reverse tcp:9099 tcp:9099`. Skipped when the
 * emulators aren't reachable, so connected test runs without them still pass.
 *
 * Fresh accounts are made for each test, so B's demo accounts stay as seeded; the one exception
 * is the 4th-request check, which needs demo-requester's old account and closes what it opens.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EmulatorBatchesTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var offers: OfferRepository
    @Inject lateinit var inbox: InboxRepository
    @Inject lateinit var safety: SafetyRepository
    @Inject lateinit var donors: io.github.liquidlyf.core.data.donor.DonorRepository

    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable on ${BuildConfig.EMULATOR_HOST}", BuildConfig.USE_FIREBASE_EMULATOR && emulatorsUp())
        hilt.inject()
    }

    // ---- profile (contract 1–5) ----

    @Test
    fun profileSetupEditUnlistAndRelist() = runBlocking {
        val email = newVerifiedUser("profile")
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(BloodGroup.O_NEG, listed = true)).ok()
        var me = profiles.profile(uid, refresh = true).ok()!!
        assertEquals(0, profiles.stats(uid, refresh = true).ok()!!.openRequests)
        assertTrue(cardExists(uid))

        profiles.update(me, profileDraft(BloodGroup.O_NEG, listed = true).copy(available = false)).ok()
        me = profiles.profile(uid, refresh = true).ok()!!
        assertFalse(me.available)
        assertEquals(false, db.donorRef(uid).get(Source.SERVER).await().getBoolean(DonorDoc.AVAILABLE))

        profiles.update(me, profileDraft(BloodGroup.O_NEG, listed = false)).ok()
        assertFalse(cardExists(uid))
        me = profiles.profile(uid, refresh = true).ok()!!
        profiles.update(me, profileDraft(BloodGroup.O_NEG, listed = true)).ok()
        assertTrue(cardExists(uid))
        me = profiles.profile(uid, refresh = true).ok()!!
        profiles.markInboxSeen(me).ok()
    }

    @Test
    fun selfReportedDonationCountsAndUncounts() = runBlocking {
        val email = newVerifiedUser("self")
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(BloodGroup.A_POS, listed = true)).ok()
        var me = profiles.profile(uid, refresh = true).ok()!!
        profiles.addSelfDonation(me, Instant.now().minus(Duration.ofDays(200)), BloodComponent.WHOLE_BLOOD, "Test Hospital").ok()
        me = profiles.profile(uid, refresh = true).ok()!!
        assertEquals(1, me.donationCount)
        assertEquals(0, me.verifiedDonations)
        val donation = profiles.donations(uid).ok().single()
        assertEquals(DonationSource.SELF, donation.source)
        profiles.deleteSelfDonation(me, donation).ok()
        assertEquals(0, profiles.profile(uid, refresh = true).ok()!!.donationCount)
    }

    @Test
    fun unknownBloodGroupCanRequestButNotOfferUntilAdded() = runBlocking {
        // P1: a profile without a group (B's 52e9ece rules).
        val requesterEmail = newVerifiedUser("needs")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.B_POS, listed = false).copy(bloodGroup = null)).ok()
        val requester = profiles.profile(requesterUid, refresh = true).ok()!!
        assertNull(requester.bloodGroup)
        val rid = requests.create(requester, requestDraft()).ok()

        val noGroupEmail = newVerifiedUser("nogroup")
        val noGroupUid = auth.uid!!
        profiles.create(noGroupUid, noGroupEmail, profileDraft(BloodGroup.B_POS, listed = false).copy(bloodGroup = null)).ok()
        var me = profiles.profile(noGroupUid, refresh = true).ok()!!
        assertEquals(AppResult.Failure(AppError.InvalidInput), offers.offer(serverRequest(rid), me, null))
        // Listing without a group is refused before it reaches the server.
        assertEquals(AppResult.Failure(AppError.InvalidInput), profiles.update(me, profileDraft(BloodGroup.B_POS, listed = true).copy(bloodGroup = null)))

        // Adding the group later, and listing, works; then an offer goes through.
        profiles.update(me, profileDraft(BloodGroup.B_POS, listed = true)).ok()
        me = profiles.profile(noGroupUid, refresh = true).ok()!!
        assertEquals(BloodGroup.B_POS, me.bloodGroup)
        offers.offer(serverRequest(rid), me, null).ok()

        signIn(requesterEmail)
        requests.close(serverRequest(rid), RequestStatus.CANCELLED, null).ok()
    }

    // ---- requests (contract 6–8) ----

    @Test
    fun newAccountHoldsOneOpenRequestAndClosingFreesTheSlot() = runBlocking {
        val email = newVerifiedUser("limit1")
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(BloodGroup.B_POS, listed = false)).ok()
        val me = profiles.profile(uid, refresh = true).ok()!!
        val first = requests.create(me, requestDraft()).ok()
        assertEquals(1, profiles.stats(uid, refresh = true).ok()!!.openRequests)
        assertEquals(AppResult.Failure(AppError.PermissionDenied), requests.create(me, requestDraft()))

        requests.edit(serverRequest(first), notes = "Call before 5 pm, bkash not needed", unitsNeeded = 2, neededBy = null).ok()
        assertTrue(serverRequest(first).flagged)
        requests.close(serverRequest(first), RequestStatus.CANCELLED, "Found a donor elsewhere").ok()
        assertEquals(0, profiles.stats(uid, refresh = true).ok()!!.openRequests)
        val second = requests.create(me, requestDraft()).ok()
        assertNotNull(requests.contact(second).ok())
        requests.close(serverRequest(second), RequestStatus.CANCELLED, null).ok()
    }

    @Test
    fun anOldAccountsFourthOpenRequestIsRejected() = runBlocking {
        signIn("demo-requester@example.test")
        val uid = auth.uid!!
        val me = profiles.profile(uid, refresh = true).ok()!!
        val start = profiles.stats(uid, refresh = true).ok()!!.openRequests
        val opened = mutableListOf<String>()
        try {
            repeat(3 - start) { opened += requests.create(me, requestDraft()).ok() }
            assertEquals(3, profiles.stats(uid, refresh = true).ok()!!.openRequests)
            assertEquals(AppResult.Failure(AppError.PermissionDenied), requests.create(me, requestDraft()))
        } finally {
            opened.forEach { requests.close(serverRequest(it), RequestStatus.CANCELLED, "test cleanup") }
        }
        assertEquals(start, profiles.stats(uid, refresh = true).ok()!!.openRequests)
    }

    // ---- offers (contract 9–12) and inbox ----

    @Test
    fun offerConfirmDonateFulfilsTheRequestAndVerifiesTheDonation() = runBlocking {
        val donorEmail = newVerifiedUser("donor")
        val donorUid = auth.uid!!
        profiles.create(donorUid, donorEmail, profileDraft(BloodGroup.B_POS, listed = true)).ok()

        val requesterEmail = newVerifiedUser("requester")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.B_POS, listed = false)).ok()
        val requester = profiles.profile(requesterUid, refresh = true).ok()!!
        val rid = requests.create(requester, requestDraft(units = 1)).ok()

        signIn(donorEmail)
        val donor = profiles.profile(donorUid, refresh = true).ok()!!
        offers.offer(serverRequest(rid), donor, "Can come in an hour").ok()
        assertEquals(ResponseStatus.OFFERED, offers.myOffer(rid, donorUid).ok()!!.status)
        assertNotNull("A donor who offered can read the contact", requests.contact(rid).ok())

        signIn(requesterEmail)
        val offer = offers.myOfferAsRequester(rid, requesterUid)
        assertEquals(donor.phone, offer.donorPhone)
        offers.decide(serverRequest(rid), offer, ResponseStatus.CONFIRMED).ok()
        offers.decide(serverRequest(rid), offers.myOfferAsRequester(rid, requesterUid), ResponseStatus.DONATED).ok()
        val done = serverRequest(rid)
        assertEquals(RequestStatus.FULFILLED, done.status)
        assertEquals(1, done.unitsFulfilled)
        assertEquals(ResponseStatus.DONATED, done.responders.getValue(donorUid).status)
        assertEquals(0, profiles.stats(requesterUid, refresh = true).ok()!!.openRequests)
        assertEquals(InboxType.NEW_OFFER, inbox.page(requesterUid).ok().single().type)

        signIn(donorEmail)
        // In the app this is the server time the requester marked DONATED; the rules need a past time.
        val donatedAt = Instant.now().minus(Duration.ofMinutes(2))
        profiles.recordVerifiedDonation(profiles.profile(donorUid, refresh = true).ok()!!, done, donatedAt).ok()
        val after = profiles.profile(donorUid, refresh = true).ok()!!
        assertEquals(1, after.verifiedDonations)
        assertEquals(1, after.donationCount)
        assertEquals(
            setOf(InboxType.OFFER_CONFIRMED, InboxType.DONATION_RECORDED),
            inbox.page(donorUid).ok().map { it.type }.toSet(),
        )
    }

    @Test
    fun notForMeAndTheAvailabilitySwitchPassTheRules() = runBlocking {
        val email = newVerifiedUser("hide")
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft(BloodGroup.B_POS, listed = true)).ok()

        profiles.hideRequest(profiles.profile(uid, refresh = true).ok()!!, "some-request").ok()
        assertEquals(listOf("some-request"), profiles.profile(uid, refresh = true).ok()!!.hiddenRequestIds)

        profiles.setAvailable(profiles.profile(uid, refresh = true).ok()!!, false).ok()
        assertFalse(profiles.profile(uid, refresh = true).ok()!!.available)
        // The donor card follows in the same batch, so donor search and alerts stop too.
        assertEquals(false, db.donorRef(uid).get(Source.SERVER).await().getBoolean(DonorDoc.AVAILABLE))
    }

    @Test
    fun withdrawAndReport() = runBlocking {
        val donorEmail = newVerifiedUser("withdraw")
        val donorUid = auth.uid!!
        profiles.create(donorUid, donorEmail, profileDraft(BloodGroup.B_POS, listed = true)).ok()
        val requesterEmail = newVerifiedUser("reported")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.B_POS, listed = false)).ok()
        val rid = requests.create(profiles.profile(requesterUid, refresh = true).ok()!!, requestDraft()).ok()

        signIn(donorEmail)
        offers.offer(serverRequest(rid), profiles.profile(donorUid, refresh = true).ok()!!, null).ok()
        offers.withdraw(serverRequest(rid), donorUid).ok()
        assertEquals(ResponseStatus.WITHDRAWN, serverRequest(rid).responders.getValue(donorUid).status)
        safety.reportRequest(serverRequest(rid), donorUid, ReportReason.ASKING_MONEY, "Asked for bKash").ok()
        assertEquals(1, serverRequest(rid).reportCount)

        signIn(requesterEmail)
        requests.close(serverRequest(rid), RequestStatus.CANCELLED, null).ok()
    }

    // ---- helpers ----

    // ---- Find donors and ask to donate (6.1, 6.2; contract 14) ----

    @Test
    fun findAListedDonorAndAskThemOnce() = runBlocking {
        val donorEmail = newVerifiedUser("find-d")
        val donorUid = auth.uid!!
        profiles.create(donorUid, donorEmail, profileDraft(BloodGroup.B_POS, listed = true)).ok()
        assertFalse("a donor card never holds a phone number", db.donorRef(donorUid).get(Source.SERVER).await().data!!.containsKey("phone"))

        val requesterEmail = newVerifiedUser("find-r")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.A_POS, listed = false)).ok()
        val requester = profiles.profile(requesterUid, refresh = true).ok()!!
        val rid = requests.create(requester, requestDraft(units = 1)).ok()

        // Earlier runs leave many cards in this district: page until the new donor shows up.
        val search = io.github.liquidlyf.core.data.donor.DonorSearch(setOf(BloodGroup.B_POS), dhakaSouth.district, null, eligibleNowOnly = true)
        val now = Instant.now()
        var after: io.github.liquidlyf.core.model.DonorCard? = null
        var found: io.github.liquidlyf.core.model.DonorCard? = null
        repeat(50) {
            if (found != null) return@repeat
            val page = donors.search(search, now, after).ok()
            found = page.firstOrNull { it.uid == donorUid }
            after = page.lastOrNull() ?: return@repeat
        }
        val card = found ?: throw AssertionError("the listed B+ donor wasn't in the search")
        assertTrue(donors.count(search, now).ok() >= 1)
        assertTrue(donors.search(search.copy(upazila = dhakaSouth.upazila), now).ok().isNotEmpty())

        val request = serverRequest(rid)
        safety.askToDonate(request, card).ok()
        assertEquals(1, serverRequest(rid).askCount)
        assertEquals(donorUid, db.requestRef(rid).get(Source.SERVER).await().getString(RequestDoc.LAST_ASKED_UID))
        // The ask's id is fixed per donor and request: asking again is refused.
        assertEquals(AppResult.Failure(io.github.liquidlyf.core.util.AppError.PermissionDenied), safety.askToDonate(serverRequest(rid), card))
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private suspend fun OfferRepository.myOfferAsRequester(rid: String, requesterUid: String) =
        withTimeout(10_000) { responders(rid, requesterUid).first { it.isNotEmpty() }.single() }

    private suspend fun serverRequest(id: String): BloodRequest {
        val snap = db.requestRef(id).get(Source.SERVER).await()
        return RequestDoc.fromMap(snap.id, snap.data!!)!!
    }

    private suspend fun cardExists(uid: String) = db.donorRef(uid).get(Source.SERVER).await().exists()

    private suspend fun signIn(email: String) {
        auth.signOut()
        auth.signInWithEmailAndPassword(email, PASSWORD).await()
    }

    /** Creates an account and marks its email verified with the Auth emulator's admin API. */
    private suspend fun newVerifiedUser(tag: String): String {
        val email = "it-$tag-${System.nanoTime()}@example.test"
        auth.signOut()
        val uid = auth.createUserWithEmailAndPassword(email, PASSWORD).await().user!!.uid
        withContext(Dispatchers.IO) {
            val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/projects/$PROJECT/accounts:update")
            val http = url.openConnection() as HttpURLConnection
            try {
                http.requestMethod = "POST"
                http.doOutput = true
                http.setRequestProperty("Authorization", "Bearer owner")
                http.setRequestProperty("Content-Type", "application/json")
                http.outputStream.use { it.write("""{"localId":"$uid","emailVerified":true}""".toByteArray()) }
                check(http.responseCode == 200) { "Auth emulator refused to verify $email: ${http.responseCode}" }
            } finally {
                http.disconnect()
            }
        }
        auth.currentUser!!.reload().await()
        auth.currentUser!!.getIdToken(true).await()
        return email
    }

    private fun emulatorsUp(): Boolean = listOf(8085, 9099).all { port ->
        try {
            Socket().use { it.connect(InetSocketAddress(BuildConfig.EMULATOR_HOST, port), 1_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun profileDraft(group: BloodGroup, listed: Boolean) = ProfileDraft(
        fullName = "Test ${group.code} Donor",
        phone = "+8801712345678",
        bloodGroup = group,
        gender = Gender.MALE,
        birthYear = 1995,
        weightOk = true,
        area = dhakaSouth,
        available = true,
        lastDonationAt = null,
        showInDonorSearch = listed,
        locale = AppLocale.EN,
        termsVersion = "test",
    )

    private fun requestDraft(units: Int = 2) = RequestDraft(
        bloodGroup = BloodGroup.B_POS,
        component = BloodComponent.WHOLE_BLOOD,
        acceptCompatible = false,
        unitsNeeded = units,
        condition = null,
        hospitalId = null,
        hospitalName = "Test Medical College Hospital",
        hospitalAddress = null,
        division = dhakaSouth.division,
        district = dhakaSouth.district,
        upazila = dhakaSouth.upazila,
        location = null,
        urgency = Urgency.URGENT,
        neededBy = Instant.now().plus(Duration.ofHours(6)),
        notes = null,
        contactName = "Test Contact",
        contactPhone = "+8801812345678",
        patientName = null,
        wardOrBed = null,
    )

    private companion object {
        const val PASSWORD = "password123"
        const val PROJECT = "demo-liquidlyf"
    }
}
