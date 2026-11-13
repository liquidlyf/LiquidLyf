package io.github.liquidlyf.core.firebase.dto

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestLocation
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirestoreDocsTest {

    private val serverNow = Instant.parse("2026-11-13T06:00:00Z")
    private val uid = "uidDonor01"

    /** What the server does with FieldValue.serverTimestamp(): replaces it with request.time. */
    private fun resolve(m: Map<String, Any?>): Map<String, Any?> = m.mapValues { (_, v) ->
        @Suppress("UNCHECKED_CAST")
        when {
            isServerTime(v) -> serverNow.toTimestamp()
            v is Map<*, *> -> resolve(v as Map<String, Any?>)
            v is Int -> v.toLong() // Firestore hands numbers back as Long
            else -> v
        }
    }

    private fun isServerTime(v: Any?) = v != null && v.javaClass == FieldValue.serverTimestamp().javaClass
    private fun isDelete(v: Any?) = v != null && v.javaClass == FieldValue.delete().javaClass

    private val profileDraft = ProfileDraft(
        fullName = "  Md. Abdul   Karim ",
        phone = "+8801712345678",
        bloodGroup = BloodGroup.B_POS,
        gender = Gender.MALE,
        birthYear = 1996,
        weightOk = true,
        area = Area("dhaka-div", "dhaka", "dhanmondi"),
        available = true,
        lastDonationAt = Instant.parse("2026-05-01T00:00:00Z"),
        showInDonorSearch = true,
        locale = AppLocale.BN,
        termsVersion = "2026-11",
    )

    private val requestDraft = RequestDraft(
        bloodGroup = BloodGroup.A_NEG,
        component = BloodComponent.PLATELETS,
        acceptCompatible = true,
        unitsNeeded = 2,
        condition = PatientCondition.DENGUE,
        hospitalId = null,
        hospitalName = " Dhaka Medical College Hospital ",
        hospitalAddress = "  ",
        division = "dhaka-div",
        district = "dhaka",
        upazila = null,
        location = RequestLocation(23.7256, 90.3976, "wh0r3"),
        urgency = Urgency.URGENT,
        neededBy = Instant.parse("2026-11-14T10:30:15.789Z"),
        notes = "Please call, will pay taka for transport",
        contactName = "Rahim  Uddin",
        contactPhone = "+8801812345678",
        patientName = null,
        wardOrBed = "Ward 7",
    )

    // ---- every write uses only keys the rules allow ----

    @Test
    fun `profile, card and stats writes use only the rules' keys`() {
        val user = UserDoc.createMap(profileDraft, "rahim@example.test")
        assertTrue(RulesKeys.inFunction("userKeys").containsAll(user.keys))
        val profile = UserDoc.fromMap(uid, resolve(user))!!
        val card = DonorDoc.map(profile, Instant.EPOCH, lastActiveAtIsServerTime = true)
        assertEquals(RulesKeys.inFunction("validDonorCard"), card.keys)
        assertTrue(RulesKeys.inMatch("userStats").containsAll(UserStatsDoc.createMap().keys))
    }

    @Test
    fun `request, contact, offer, donation, inbox and report writes use only the rules' keys`() {
        val request = RequestDoc.createMap(requestDraft, uid, "Abdul K.")
        assertTrue(RulesKeys.inFunction("requestKeys").containsAll(request.keys))
        assertTrue(RulesKeys.inFunction("validContact").containsAll(ContactDoc.createMap(requestDraft, uid).keys))
        val offer = ResponseDoc.offerMap("req1", "uidReq", uid, "Abdul K.", BloodGroup.A_NEG, "+8801712345678", "On my way")
        assertEquals(RulesKeys.inFunction("validOffer"), offer.keys)
        val donationKeys = RulesKeys.inFunction("validDonation")
        assertEquals(donationKeys, DonationDoc.verifiedMap("req1", serverNow, BloodComponent.WHOLE_BLOOD, "DMCH").keys)
        assertTrue(donationKeys.containsAll(DonationDoc.selfMap(serverNow, BloodComponent.WHOLE_BLOOD, null).keys))
        assertEquals(RulesKeys.inFunction("validInboxItem"), InboxDoc.createMap(InboxType.NEW_OFFER, "req1", uid).keys)
        val report = ReportDoc.createMap(uid, ReportTarget.REQUEST, "req1", ReportReason.ASKING_MONEY, "asked for bKash")
        assertEquals(RulesKeys.inFunction("validReport"), report.keys)
    }

    // ---- profile ----

    @Test
    fun `profile setup writes what the rules require on create`() {
        val m = UserDoc.createMap(profileDraft, "rahim@example.test")
        assertEquals("Md. Abdul Karim", m[UserDoc.FULL_NAME])
        assertEquals(0, m[UserDoc.DONATION_COUNT])
        assertEquals(0, m[UserDoc.VERIFIED_DONATIONS])
        assertFalse(UserDoc.PHONE_VERIFIED in m)
        assertFalse(UserDoc.ROLE in m)
        assertFalse(UserDoc.LAST_DONATION_ID in m)
        // PDPA: a listed donor's consent is recorded.
        assertTrue(isServerTime(m[UserDoc.CONSENT_AT]))
        listOf(UserDoc.CREATED_AT, UserDoc.UPDATED_AT, UserDoc.TERMS_ACCEPTED_AT, UserDoc.LAST_ACTIVE_AT)
            .forEach { assertTrue(it, isServerTime(m[it])) }

        val unlisted = UserDoc.createMap(profileDraft.copy(showInDonorSearch = false), "rahim@example.test")
        assertFalse(UserDoc.CONSENT_AT in unlisted)
    }

    @Test
    fun `profile round-trips through Firestore values`() {
        val p = UserDoc.fromMap(uid, resolve(UserDoc.createMap(profileDraft, "rahim@example.test")))!!
        assertEquals(BloodGroup.B_POS, p.bloodGroup)
        assertEquals(Area("dhaka-div", "dhaka", "dhanmondi"), p.area)
        assertEquals(1996, p.birthYear)
        assertEquals(AppLocale.BN, p.locale)
        assertEquals(profileDraft.lastDonationAt, p.lastDonationAt)
        assertEquals(serverNow, p.consentAt)
        assertEquals(emptyList<String>(), p.hiddenRequestIds)
        assertFalse(p.phoneVerified)
    }

    @Test
    fun `the donor card mirrors the profile and shows a short name`() {
        val p = UserDoc.fromMap(uid, resolve(UserDoc.createMap(profileDraft, "rahim@example.test")))!!
        val card = DonorDoc.map(p, Instant.EPOCH, lastActiveAtIsServerTime = false)
        assertEquals("Abdul K.", card[DonorDoc.DISPLAY_NAME])
        assertEquals("B_POS", card[DonorDoc.BLOOD_GROUP])
        assertEquals(Timestamp(0, 0), card[DonorDoc.ELIGIBLE_FROM])
        assertEquals(p.lastActiveAt!!.toTimestamp(), card[DonorDoc.LAST_ACTIVE_AT])
        val read = DonorDoc.fromMap(uid, resolve(card))!!
        assertEquals(Instant.EPOCH, read.eligibleFrom)
        assertEquals(p.area, read.area)
    }

    @Test
    fun `a profile edit writes only what changed, and a new phone loses its badge`() {
        val before = UserDoc.fromMap(uid, resolve(UserDoc.createMap(profileDraft, "rahim@example.test")))!!
            .copy(phoneVerified = true)
        val after = UserDoc.applyDraft(
            before,
            profileDraft.copy(phone = "+8801912345678", available = false, lastDonationAt = null),
            serverNow,
        )
        val m = UserDoc.updateMap(before, after)
        assertEquals(
            setOf(UserDoc.PHONE, UserDoc.PHONE_VERIFIED, UserDoc.AVAILABLE, UserDoc.LAST_DONATION_AT, UserDoc.UPDATED_AT),
            m.keys,
        )
        assertEquals(false, m[UserDoc.PHONE_VERIFIED])
        assertTrue(isDelete(m[UserDoc.LAST_DONATION_AT]))
        assertTrue(isServerTime(m[UserDoc.UPDATED_AT]))
    }

    @Test
    fun `server-time fields are written as the server time even when unchanged`() {
        val p = UserDoc.fromMap(uid, resolve(UserDoc.createMap(profileDraft, "rahim@example.test")))!!
        val m = UserDoc.updateMap(p, p, serverTimeFields = setOf(UserDoc.LAST_ACTIVE_AT))
        assertEquals(setOf(UserDoc.LAST_ACTIVE_AT, UserDoc.UPDATED_AT), m.keys)
        assertTrue(isServerTime(m[UserDoc.LAST_ACTIVE_AT]))
    }

    @Test
    fun `listing again records a new consent time`() {
        val unlisted = UserDoc.fromMap(uid, resolve(UserDoc.createMap(profileDraft.copy(showInDonorSearch = false), "a@b.test")))!!
        val later = serverNow.plusSeconds(3600)
        val relisted = UserDoc.applyDraft(unlisted, profileDraft, later)
        assertEquals(later, relisted.consentAt)
        assertTrue(relisted.showInDonorSearch)
    }

    // ---- requests ----

    @Test
    fun `a new request starts OPEN with zero counters and Worker-owned start values`() {
        val m = RequestDoc.createMap(requestDraft, uid, "Abdul K.")
        assertEquals("OPEN", m[RequestDoc.STATUS])
        listOf(RequestDoc.UNITS_FULFILLED, RequestDoc.RESPONSE_COUNT, RequestDoc.REPORT_COUNT, RequestDoc.ASK_COUNT, RequestDoc.FANOUT_TIER)
            .forEach { assertEquals(it, 0, m[it]) }
        assertEquals(emptyMap<String, Any?>(), m[RequestDoc.RESPONDERS])
        listOf(RequestDoc.NEXT_TIER_AT, RequestDoc.CREATED_AT, RequestDoc.UPDATED_AT).forEach { assertTrue(it, isServerTime(m[it])) }
        listOf(RequestDoc.CLOSED_REASON, RequestDoc.LAST_RESPONDER_UID, RequestDoc.LAST_ASKED_UID, RequestDoc.UPAZILA,
            RequestDoc.HOSPITAL_ID, RequestDoc.HOSPITAL_ADDRESS).forEach { assertFalse(it, it in m) }
    }

    @Test
    fun `neededBy is whole seconds and expiresAt is exactly 24 hours later`() {
        val m = RequestDoc.createMap(requestDraft, uid, "Abdul K.")
        val neededBy = m[RequestDoc.NEEDED_BY] as Timestamp
        assertEquals(0, neededBy.nanoseconds)
        assertEquals(Instant.parse("2026-11-14T10:30:15Z"), neededBy.toInstantCompat())
        assertEquals(Instant.parse("2026-11-15T10:30:15Z"), (m[RequestDoc.EXPIRES_AT] as Timestamp).toInstantCompat())
    }

    @Test
    fun `accepted groups come from the compatibility table and notes about money are flagged`() {
        val m = RequestDoc.createMap(requestDraft, uid, "Abdul K.")
        assertEquals(listOf("A_NEG", "AB_NEG"), m[RequestDoc.ACCEPTED_GROUPS])
        assertEquals(true, m[RequestDoc.FLAGGED])
        val exact = RequestDoc.createMap(requestDraft.copy(acceptCompatible = false, notes = "  "), uid, "Abdul K.")
        assertEquals(listOf("A_NEG"), exact[RequestDoc.ACCEPTED_GROUPS])
        assertEquals(false, exact[RequestDoc.FLAGGED])
        assertFalse(RequestDoc.NOTES in exact)
    }

    @Test
    fun `a request round-trips, including responders`() {
        val stored = resolve(RequestDoc.createMap(requestDraft, uid, "Abdul K.")) + mapOf(
            RequestDoc.RESPONDERS to mapOf(
                "d1" to Responders.entry(Responder("Nusrat J.", BloodGroup.A_NEG, ResponseStatus.CONFIRMED)),
                "bad" to mapOf("name" to "x"),
            ),
        )
        val r = RequestDoc.fromMap("req1", stored)!!
        assertEquals("req1", r.id)
        assertEquals(listOf(BloodGroup.A_NEG, BloodGroup.AB_NEG), r.acceptedGroups)
        assertEquals("Dhaka Medical College Hospital", r.hospitalName)
        assertNull(r.hospitalAddress)
        assertEquals(RequestLocation(23.7256, 90.3976, "wh0r3"), r.location)
        assertEquals(mapOf("d1" to Responder("Nusrat J.", BloodGroup.A_NEG, ResponseStatus.CONFIRMED)), r.responders)
        assertEquals(RequestStatus.OPEN, r.status)
        assertEquals(2, r.unitsStillNeeded)
        assertTrue(r.isActive(serverNow))
        assertFalse(r.isActive(Instant.parse("2026-11-15T10:30:15Z")))
        assertEquals("responders.d1", Responders.path("d1"))
    }

    @Test
    fun `contact and offer carry trimmed text and the donor's consent`() {
        val c = ContactDoc.createMap(requestDraft, uid)
        assertEquals("Rahim Uddin", c[ContactDoc.CONTACT_NAME])
        assertEquals("Ward 7", c[ContactDoc.WARD_OR_BED])
        assertFalse(ContactDoc.PATIENT_NAME in c)
        assertEquals(uid, ContactDoc.fromMap(c)!!.requesterUid)

        val offer = ResponseDoc.offerMap("req1", "uidReq", uid, "Abdul K.", BloodGroup.A_NEG, "+8801712345678", "  ")
        assertEquals("OFFERED", offer[ResponseDoc.STATUS])
        assertFalse(ResponseDoc.MESSAGE in offer)
        val read = ResponseDoc.fromMap(resolve(offer))!!
        assertEquals(ResponseStatus.OFFERED, read.status)
        assertEquals("+8801712345678", read.donorPhone)
        assertEquals(setOf(ResponseDoc.STATUS, ResponseDoc.UPDATED_AT), ResponseDoc.statusMap(ResponseStatus.WITHDRAWN).keys)
    }

    // ---- activity ----

    @Test
    fun `inbox ids follow the rules' patterns`() {
        assertEquals("NEW_OFFER_req1_uidD", InboxDoc.docId(InboxType.NEW_OFFER, "req1", "uidD"))
        assertEquals("OFFER_CONFIRMED_req1", InboxDoc.docId(InboxType.OFFER_CONFIRMED, "req1", "uidR"))
        assertEquals("OFFER_DECLINED_req1", InboxDoc.docId(InboxType.OFFER_DECLINED, "req1", "uidR"))
        assertEquals("DONATION_RECORDED_req1", InboxDoc.docId(InboxType.DONATION_RECORDED, "req1", "uidR"))
        assertEquals("ask_req1", InboxDoc.docId(InboxType.ASKED_TO_DONATE, "req1", "uidR"))
        val item = InboxDoc.fromMap("ask_req1", resolve(InboxDoc.createMap(InboxType.ASKED_TO_DONATE, "req1", "uidR")))!!
        assertEquals(InboxType.ASKED_TO_DONATE, item.type)
        assertEquals("uid1_req1", ReportDoc.docId("uid1", "req1"))
    }

    @Test
    fun `verified donations link the request, self-reported ones never do`() {
        val v = DonationDoc.verifiedMap("req1", serverNow, BloodComponent.WHOLE_BLOOD, " DMCH ")
        assertEquals(true, v[DonationDoc.VERIFIED])
        assertEquals("REQUEST", v[DonationDoc.SOURCE])
        assertEquals("req1", v[DonationDoc.REQUEST_ID])
        val s = DonationDoc.selfMap(serverNow, BloodComponent.WHOLE_BLOOD, null)
        assertEquals(false, s[DonationDoc.VERIFIED])
        assertFalse(DonationDoc.REQUEST_ID in s)
        val read = DonationDoc.fromMap("req1", resolve(v))!!
        assertEquals("DMCH", read.hospitalName)
        assertEquals(serverNow, read.donatedAt)
    }

    // ---- reading bad documents ----

    @Test
    fun `documents with missing or unknown required values read as null, not a crash`() {
        val good = resolve(RequestDoc.createMap(requestDraft, uid, "Abdul K."))
        assertNotNull(RequestDoc.fromMap("r", good))
        assertNull(RequestDoc.fromMap("r", good - RequestDoc.NEEDED_BY))
        assertNull(RequestDoc.fromMap("r", good + (RequestDoc.STATUS to "PENDING")))
        assertNull(RequestDoc.fromMap("r", good + (RequestDoc.UNITS_NEEDED to "two")))
        assertNull(UserDoc.fromMap(uid, emptyMap()))
        assertNull(DonorDoc.fromMap(uid, mapOf(DonorDoc.DISPLAY_NAME to "A B.")))
        assertNull(ResponseDoc.fromMap(mapOf(ResponseDoc.STATUS to "OFFERED")))
        assertNull(InboxDoc.fromMap("x", mapOf(InboxDoc.TYPE to "SPAM")))
        assertNull(DonationDoc.fromMap("x", emptyMap()))
        assertNull(ContactDoc.fromMap(emptyMap()))
        assertEquals(0, UserStatsDoc.fromMap(emptyMap()).openRequests)
        assertEquals("req9", UserStatsDoc.fromMap(mapOf(UserStatsDoc.LAST_REQUEST_ID to "req9")).lastRequestId)
    }
}
