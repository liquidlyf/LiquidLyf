package io.github.liquidlyf.notifications

import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.notifications.AlertPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val alert = PushMessage.RequestAlert("r1", BloodGroup.O_POS, "BD4041", Urgency.URGENT, 1)

    private fun decide(
        message: PushMessage = alert,
        myUid: String? = "me",
        mine: Boolean = false,
        requester: String? = null,
        blocked: List<String> = emptyList(),
        shown: Int = 0,
    ) = AlertPolicy.decide(message, myUid, mine, requester, blocked, shown)

    @Test
    fun `the Worker's payloads parse, and anything else is ignored`() {
        val parsed = PushMessage.parse(
            mapOf("type" to "NEW_REQUEST", "requestId" to "r1", "bloodGroup" to "O_POS", "district" to "BD4041", "urgency" to "URGENT", "tier" to "1"),
        )
        assertEquals(alert, parsed)
        assertEquals(
            PushMessage.Personal(PersonalPush.DONATION_RECORDED, "xxdbMxJ5jMf572VKw5eR"),
            PushMessage.parse(mapOf("type" to "DONATION_RECORDED", "requestId" to "xxdbMxJ5jMf572VKw5eR")),
        )
        // Unknown fields degrade gracefully; a bad id or type is dropped.
        val sparse = PushMessage.parse(mapOf("type" to "NEW_REQUEST", "requestId" to "r2", "bloodGroup" to "??"))
        assertEquals(PushMessage.RequestAlert("r2", null, null, null, null), sparse)
        assertNull(PushMessage.parse(mapOf("type" to "NEW_REQUEST", "requestId" to "../evil")))
        assertNull(PushMessage.parse(mapOf("type" to "SOMETHING_ELSE", "requestId" to "r1")))
        assertNull(PushMessage.parse(mapOf("type" to "NEW_OFFER")))
    }

    @Test
    fun `the first alert is new, the second still needed, and there is no third`() {
        assertEquals(Decision.ShowRequest(stillNeeded = false), decide(shown = 0))
        assertEquals(Decision.ShowRequest(stillNeeded = true), decide(shown = 1))
        assertTrue(decide(shown = 2) is Decision.Drop)
    }

    @Test
    fun `my own requests and blocked requesters never alert me`() {
        assertTrue(decide(mine = true) is Decision.Drop)
        assertTrue(decide(requester = "me") is Decision.Drop)
        assertTrue(decide(requester = "pest", blocked = listOf("pest")) is Decision.Drop)
        assertEquals(Decision.ShowRequest(false), decide(requester = "someone", blocked = listOf("pest")))
    }

    @Test
    fun `a requester I blocked sends me nothing personal, and a donor's offer is unaffected`() {
        val asked = PushMessage.Personal(PersonalPush.ASKED_TO_DONATE, "r1")
        assertTrue(decide(asked, requester = "pest", blocked = listOf("pest")) is Decision.Drop)
        assertTrue(decide(PushMessage.Personal(PersonalPush.OFFER_CONFIRMED, "r1"), requester = "pest", blocked = listOf("pest")) is Decision.Drop)
        assertEquals(Decision.ShowPersonal, decide(asked, requester = "someone", blocked = listOf("pest")))
        // A NEW_OFFER comes from a donor, not the requester: the requester lookup doesn't apply.
        assertFalse(PersonalPush.NEW_OFFER.fromRequester)
        assertEquals(Decision.ShowPersonal, decide(PushMessage.Personal(PersonalPush.NEW_OFFER, "r1"), requester = "pest", blocked = listOf("pest")))
    }

    @Test
    fun `signed out nothing is shown, and personal pushes always are`() {
        assertTrue(decide(myUid = null) is Decision.Drop)
        val personal = PushMessage.Personal(PersonalPush.NEW_OFFER, "r1")
        assertTrue(decide(personal, myUid = null) is Decision.Drop)
        assertEquals(Decision.ShowPersonal, decide(personal, mine = true, shown = 5))
    }
}
