package io.github.liquidlyf.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.data.moderation.ModerationRepository
import io.github.liquidlyf.core.data.moderation.RequestAction
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.UserStatsDoc
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
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
 * Phase 7.4/7.5 on the emulators against session B's rules (contract 17): an admin dismisses,
 * hides, restores and cancels, each with its adminLog entry; a cancel gives the owner's slot back.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModerationEmulatorTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var safety: SafetyRepository
    @Inject lateinit var moderation: ModerationRepository

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private suspend fun request(rid: String): BloodRequest = RequestDoc.fromMap(rid, db.requestRef(rid).get(Source.SERVER).await().data!!)!!

    private fun draft() = RequestDraft(
        bloodGroup = BloodGroup.B_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Test Hospital", hospitalAddress = null,
        division = "BD30", district = "BD3026", upazila = "BD30262000", location = null, urgency = Urgency.URGENT,
        neededBy = Instant.now().plus(Duration.ofHours(6)), notes = null,
        contactName = "Test Contact", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )

    @Test
    fun anAdminActsOnReportsAndEveryActionIsLogged() = runBlocking {
        // A requester posts; two people report: the request, and the requester as a person.
        val ownerEmail = EmulatorAccounts.newVerifiedUser(auth, "mod-owner")
        val ownerUid = auth.uid!!
        profiles.create(ownerUid, ownerEmail, TestData.draft().copy(bloodGroup = BloodGroup.B_POS)).ok()
        val rid = requests.create(profiles.profile(ownerUid, refresh = true).ok()!!, draft()).ok()
        val slotsBefore = db.statsRef(ownerUid).get(Source.SERVER).await().getLong(UserStatsDoc.OPEN_REQUESTS)!!

        val reporterEmail = EmulatorAccounts.newVerifiedUser(auth, "mod-reporter")
        val reporterUid = auth.uid!!
        profiles.create(reporterUid, reporterEmail, TestData.draft()).ok()
        safety.reportRequest(request(rid), reporterUid, ReportReason.FAKE_REQUEST, "Looks fake").ok()
        safety.reportUser(ownerUid, reporterUid, ReportReason.ABUSIVE, null).ok()
        // A non-admin can't read the reports.
        assertEquals(AppResult.Failure(AppError.PermissionDenied), moderation.openReports())

        // An admin (the claim, as setAdmin.mjs sets it in production).
        EmulatorAccounts.newVerifiedUser(auth, "mod-admin")
        val adminUid = auth.uid!!
        EmulatorAccounts.setClaims(adminUid, """{"admin":true}""")
        auth.currentUser!!.getIdToken(true).await()

        val open = moderation.openReports().ok().filter { it.targetId == rid || it.targetId == ownerUid }
        val requestReport = open.single { it.targetType == ReportTarget.REQUEST }
        val personReport = open.single { it.targetType == ReportTarget.USER }

        moderation.act(adminUid, RequestAction.HIDE, request(rid), requestReport, "checking").ok()
        assertEquals(RequestStatus.REMOVED, request(rid).status)
        moderation.act(adminUid, RequestAction.RESTORE, request(rid), null, null).ok()
        assertEquals(RequestStatus.OPEN, request(rid).status)
        moderation.act(adminUid, RequestAction.CANCEL, request(rid), null, "fake after all").ok()
        assertEquals(RequestStatus.CANCELLED, request(rid).status)
        val slotsAfter = db.statsRef(ownerUid).get(Source.SERVER).await().getLong(UserStatsDoc.OPEN_REQUESTS)!!
        assertEquals("the cancel gives the owner's slot back (B15)", slotsBefore - 1, slotsAfter)

        moderation.markPersonHandled(adminUid, personReport, "banned with the script").ok()
        val stillOpen = moderation.openReports().ok().map { it.id }
        assertTrue(requestReport.id !in stillOpen && personReport.id !in stillOpen)

        val log = db.collection("adminLog").whereEqualTo("adminUid", adminUid).get(Source.SERVER).await().documents.map { it.getString("action") }
        assertEquals(setOf("REMOVE_REQUEST", "RESTORE_REQUEST", "CANCEL_REQUEST", "ACTION_REPORT"), log.toSet())
        auth.signOut()
    }
}
