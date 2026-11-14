package io.github.liquidlyf.feature.request

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.data.backend.WorkerClient
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.hospital.HospitalRepository
import io.github.liquidlyf.core.data.profile.FirestoreProfileRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.FirestoreOfferRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.NetworkMonitor
import io.github.liquidlyf.feature.request.create.CreateRequestEvent
import io.github.liquidlyf.feature.request.create.CreateRequestViewModel
import io.github.liquidlyf.feature.request.detail.RequestDetailViewModel
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Phase 4.8: the core loop through the real ViewModels, rules and (if running) the local Worker,
 * on the Firebase emulators: A posts → B offers → B sees the contact → A confirms → A marks
 * donated → B's history shows 1 verified donation. Plus: two donors offering at the same moment
 * are both kept. Skipped when the emulators aren't reachable.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RequestLoopTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var offers: OfferRepository
    @Inject lateinit var safety: SafetyRepository
    @Inject lateinit var hospitals: HospitalRepository
    @Inject lateinit var geo: GeoRepository
    @Inject lateinit var worker: WorkerClient
    @Inject lateinit var eligibility: EligibilityCalculator
    @Inject lateinit var clock: Clock
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var alerts: io.github.liquidlyf.core.data.push.AlertStore

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    @After
    fun tearDown() = viewModels.forEach { it.viewModelScope.cancel() }

    @Test
    fun postOfferConfirmDonateThroughTheViewModels() = runBlocking {
        // A posts a request through the 3-step form.
        val aEmail = EmulatorAccounts.newVerifiedUser(auth, "loop-a")
        val aUid = auth.uid!!
        profiles.create(aUid, aEmail, profileDraft(BloodGroup.A_POS, listed = false, phone = "+8801812345678")).ok()
        ready(aUid)
        val create = onMain {
            CreateRequestViewModel(SavedStateHandle(), session, profiles, requests, hospitals, geo, worker, clock, appScope, network, alerts).also(viewModels::add)
        }
        waitUntil("form loaded") { create.uiState.value.loaded }
        onMain {
            create.edit { copy(bloodGroup = BloodGroup.B_POS.code, units = 1) }
            create.next()
            create.onHospitalTyped("Dhaka Medical College Hospital")
            create.edit { copy(urgency = Urgency.URGENT.code) }
            create.next()
            create.edit { copy(genuine = true, notes = "Ward 5, 3rd floor") }
            create.next()
        }
        val rid = (withTimeout(30_000) { create.eventFlow.first() } as CreateRequestEvent.Posted).requestId
        val posted = serverRequest(rid)
        assertEquals(RequestStatus.OPEN, posted.status)
        assertEquals(listOf(BloodGroup.B_POS), posted.acceptedGroups)

        // B, a matching donor, offers: the contact appears only then.
        val bEmail = EmulatorAccounts.newVerifiedUser(auth, "loop-b")
        val bUid = auth.uid!!
        profiles.create(bUid, bEmail, profileDraft(BloodGroup.B_POS, listed = true, phone = "+8801712345678")).ok()
        ready(bUid)
        val donorView = detail(rid)
        waitUntil("request shown to B") { donorView.uiState.value.request != null }
        assertNull("No contact before offering", donorView.uiState.value.contact)
        assertNull(donorView.uiState.value.offerBlock)
        onMain { donorView.offer("On my way") }
        waitUntil("B's offer and contact") { donorView.uiState.value.myStatus == ResponseStatus.OFFERED && donorView.uiState.value.contact != null }
        assertEquals("+8801812345678", donorView.uiState.value.contact!!.contactPhone)
        closeAll()

        // A sees the offer live, confirms, then marks donated.
        EmulatorAccounts.signIn(auth, aEmail)
        ready(aUid)
        val requesterView = detail(rid)
        waitUntil("A sees B's offer") { requesterView.uiState.value.responses.size == 1 }
        assertEquals("+8801712345678", requesterView.uiState.value.responses.single().donorPhone)
        onMain { requesterView.confirm(requesterView.uiState.value.responses.single()) }
        waitUntil("confirmed") { requesterView.uiState.value.responses.single().status == ResponseStatus.CONFIRMED }
        onMain { requesterView.markDonated(requesterView.uiState.value.responses.single()) }
        waitUntil("fulfilled") { requesterView.uiState.value.request?.status == RequestStatus.FULFILLED }
        closeAll()

        // B opens it again: the app records the verified donation once.
        EmulatorAccounts.signIn(auth, bEmail)
        ready(bUid)
        val donorAgain = detail(rid)
        waitUntil("B sees DONATED") { donorAgain.uiState.value.myStatus == ResponseStatus.DONATED }
        waitUntil("verified donation in B's history") {
            (profiles.donations(bUid) as? AppResult.Success)?.data?.any { it.id == rid && it.verified } == true
        }
        assertEquals(1, profiles.profile(bUid, refresh = true).ok()!!.verifiedDonations)
    }

    @Test
    fun twoDonorsOfferingAtTheSameMomentAreBothKept() = runBlocking {
        val requesterEmail = EmulatorAccounts.newVerifiedUser(auth, "race-r")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.O_POS, listed = false, phone = "+8801812345678")).ok()
        val rid = requests.create(profiles.profile(requesterUid, refresh = true).ok()!!, requestDraft()).ok()

        // Donor 1 on the app's Firebase; donor 2 on a second Firebase app, signed in at the same time.
        val d1Email = EmulatorAccounts.newVerifiedUser(auth, "race-1")
        val d1Uid = auth.uid!!
        profiles.create(d1Uid, d1Email, profileDraft(BloodGroup.O_POS, listed = true, phone = "+8801711111111")).ok()
        val (auth2, db2) = EmulatorAccounts.secondFirebase("second-donor")
        val d2Email = EmulatorAccounts.newVerifiedUser(auth2, "race-2")
        val d2Uid = auth2.uid!!
        val profiles2 = FirestoreProfileRepository(db2, eligibility, clock)
        profiles2.create(d2Uid, d2Email, profileDraft(BloodGroup.O_POS, listed = true, phone = "+8801722222222")).ok()
        val offers2 = FirestoreOfferRepository(db2, clock)

        val request = serverRequest(rid)
        val d1 = profiles.profile(d1Uid, refresh = true).ok()!!
        val d2 = profiles2.profile(d2Uid, refresh = true).ok()!!
        listOf(
            async(Dispatchers.IO) { offers.offer(request, d1, null) },
            async(Dispatchers.IO) { offers2.offer(request, d2, null) },
        ).awaitAll().forEach { it.ok() }

        val after = serverRequest(rid)
        assertEquals(setOf(d1Uid, d2Uid), after.responders.keys)
        assertEquals(2, after.responseCount)
        assertTrue(after.responders.values.all { it.status == ResponseStatus.OFFERED })
        auth2.signOut()
    }

    // ---- helpers ----

    private suspend fun detail(rid: String) = onMain {
        RequestDetailViewModel(
            SavedStateHandle(mapOf("id" to rid)), session, requests, offers, profiles, safety, worker, geo, eligibility, clock, network,
        ).also(viewModels::add)
    }

    /** Ends the screens' listeners before switching accounts (as leaving a screen does). */
    private fun closeAll() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
    }

    private suspend fun ready(uid: String) {
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(200) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private suspend fun serverRequest(id: String): BloodRequest {
        val snap = db.requestRef(id).get(Source.SERVER).await()
        return RequestDoc.fromMap(snap.id, assertNotNull(snap.data).let { snap.data!! })!!
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun profileDraft(group: BloodGroup, listed: Boolean, phone: String) = ProfileDraft(
        fullName = "Test Donor", phone = phone, bloodGroup = group, gender = Gender.FEMALE, birthYear = 1995,
        weightOk = true, area = dhakaSouth, available = true, lastDonationAt = null, showInDonorSearch = listed,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 2,
        condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital", hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(6, ChronoUnit.HOURS), notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )

}
