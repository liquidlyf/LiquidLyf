package io.github.liquidlyf.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.data.account.AccountDeleter
import io.github.liquidlyf.core.data.account.DeletionStep
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.ResponseDoc
import io.github.liquidlyf.core.firebase.dto.donorRef
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.responseRef
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Phase 3.12 on the emulators against session B's rules (write contract item 16): deleting a
 * donor who offered and a requester with an open request. What stays is anonymized; everything
 * else, and the Auth user, is gone.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountDeletionTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var offers: OfferRepository
    @Inject lateinit var deleter: AccountDeleter
    @Inject lateinit var safety: io.github.liquidlyf.core.data.safety.SafetyRepository

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private suspend fun newProfile(tag: String, group: BloodGroup, listed: Boolean): Pair<String, String> {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        val uid = auth.uid!!
        profiles.create(uid, email, TestData.draft().copy(bloodGroup = group, showInDonorSearch = listed)).ok()
        return email to uid
    }

    private fun draft() = RequestDraft(
        bloodGroup = BloodGroup.B_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Test Hospital", hospitalAddress = null,
        division = "BD30", district = "BD3026", upazila = "BD30262000", location = null, urgency = Urgency.URGENT,
        neededBy = Instant.now().plus(Duration.ofHours(6)), notes = "Please bring a friend",
        contactName = "Test Contact", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )

    @Test
    fun deletingADonorAndARequesterLeavesOnlyAnonymousTraces() = runBlocking {
        val (requesterEmail, requesterUid) = newProfile("req", BloodGroup.B_POS, listed = false)
        val rid = requests.create(profiles.profile(requesterUid, refresh = true).ok()!!, draft()).ok()

        val (donorEmail, donorUid) = newProfile("donor", BloodGroup.B_POS, listed = true)
        val request = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
        offers.offer(request, profiles.profile(donorUid, refresh = true).ok()!!, "On my way").ok()

        // ---- the donor deletes their account ----
        deleter.start(donorUid)
        val steps = mutableListOf<DeletionStep>()
        deleter.run(donorUid) { steps += it }.ok()
        assertEquals(DeletionStep.entries, steps)
        assertNull(auth.currentUser)
        assertFalse(deleter.isPending(donorUid))

        // A third person sees what is left.
        EmulatorAccounts.newVerifiedUser(auth, "viewer")
        val afterDonor = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
        assertEquals(RequestDoc.DELETED_NAME, afterDonor.responders.getValue(donorUid).name)
        assertEquals(ResponseStatus.WITHDRAWN, afterDonor.responders.getValue(donorUid).status)
        assertFalse(db.donorRef(donorUid).get(Source.SERVER).await().exists())

        // The requester sees the anonymized offer, without a phone.
        EmulatorAccounts.signIn(auth, requesterEmail)
        val response = db.responseRef(rid, donorUid).get(Source.SERVER).await()
        assertEquals(RequestDoc.DELETED_NAME, response.getString(ResponseDoc.DONOR_DISPLAY_NAME))
        assertFalse(response.contains(ResponseDoc.DONOR_PHONE))
        assertFalse(response.contains(ResponseDoc.MESSAGE))

        // The donor's account is really gone.
        auth.signOut()
        val signIn = runCatching { auth.signInWithEmailAndPassword(donorEmail, EmulatorAccounts.PASSWORD).await() }
        assertTrue(signIn.isFailure)

        // ---- the requester deletes theirs, with a request still open ----
        EmulatorAccounts.signIn(auth, requesterEmail)
        deleter.start(requesterUid)
        deleter.run(requesterUid).ok()
        assertNull(auth.currentUser)

        EmulatorAccounts.newVerifiedUser(auth, "viewer2")
        val afterRequester = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
        assertEquals(RequestDoc.DELETED_NAME, afterRequester.requesterDisplayName)
        assertEquals(RequestStatus.CANCELLED, afterRequester.status)
        assertNull(afterRequester.notes)
        assertFalse(afterRequester.flagged)
    }

    @Test
    fun aRequestHiddenByReportsIsCancelledSoTheDeletionFinishes() = runBlocking {
        // B15: a REMOVED request still holds its owner's slot; deletion must give it back.
        val (requesterEmail, requesterUid) = newProfile("hid-req", BloodGroup.B_POS, listed = false)
        val rid = requests.create(profiles.profile(requesterUid, refresh = true).ok()!!, draft()).ok()
        repeat(3) { i ->
            val (_, reporterUid) = newProfile("hid-rep$i", BloodGroup.O_POS, listed = false)
            val current = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
            safety.reportRequest(current, reporterUid, io.github.liquidlyf.core.model.ReportReason.FAKE_REQUEST, null).ok()
        }
        val hidden = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
        assertEquals("the third report hides it", RequestStatus.REMOVED, hidden.status)

        EmulatorAccounts.signIn(auth, requesterEmail)
        deleter.start(requesterUid)
        deleter.run(requesterUid).ok()
        assertNull(auth.currentUser)

        EmulatorAccounts.newVerifiedUser(auth, "hid-viewer")
        val after = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!
        assertEquals(RequestStatus.CANCELLED, after.status)
        assertEquals(RequestDoc.DELETED_NAME, after.requesterDisplayName)
    }

    @Test
    fun aResumedDeletionDoesNotRepeatWhatAlreadyHappened() = runBlocking {
        val (_, uid) = newProfile("resume", BloodGroup.O_NEG, listed = true)
        requests.create(profiles.profile(uid, refresh = true).ok()!!, draft()).ok()
        deleter.start(uid)

        // A first run is cut short after the requests step (as if the app was killed)...
        val firstRun = runCatching {
            deleter.run(uid) { step -> if (step == DeletionStep.REQUESTS_ANONYMIZED) throw IllegalStateException("killed") }
        }
        assertTrue(firstRun.isFailure || (firstRun.getOrNull() as? AppResult.Failure)?.error == AppError.Unknown)
        assertTrue(deleter.isPending(uid))

        // ...and the next launch finishes it: no second userStats −1, which the rules would refuse.
        deleter.run(uid).ok()
        assertNull(auth.currentUser)
        assertFalse(deleter.isPending(uid))
    }
}
