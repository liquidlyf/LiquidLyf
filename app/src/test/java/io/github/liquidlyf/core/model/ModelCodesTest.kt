package io.github.liquidlyf.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stored codes are part of the Firestore data model (doc 07 §1) and must never drift. */
class ModelCodesTest {

    @Test
    fun `blood group codes match the data model`() {
        assertEquals(
            listOf("A_POS", "A_NEG", "B_POS", "B_NEG", "AB_POS", "AB_NEG", "O_POS", "O_NEG"),
            BloodGroup.entries.map { it.code },
        )
    }

    @Test
    fun `every code round-trips and unknown codes give null`() {
        BloodGroup.entries.forEach { assertEquals(it, BloodGroup.fromCode(it.code)) }
        Urgency.entries.forEach { assertEquals(it, Urgency.fromCode(it.code)) }
        RequestStatus.entries.forEach { assertEquals(it, RequestStatus.fromCode(it.code)) }
        ResponseStatus.entries.forEach { assertEquals(it, ResponseStatus.fromCode(it.code)) }

        assertNull(BloodGroup.fromCode("A+"))
        assertNull(BloodGroup.fromCode(null))
        assertNull(Urgency.fromCode("SOON"))
        assertNull(RequestStatus.fromCode("Pending"))
        assertNull(ResponseStatus.fromCode(""))
    }

    @Test
    fun `exactly the four negative groups are Rh negative`() {
        assertEquals(
            setOf(BloodGroup.A_NEG, BloodGroup.B_NEG, BloodGroup.AB_NEG, BloodGroup.O_NEG),
            BloodGroup.entries.filter { it.isRhNegative }.toSet(),
        )
    }

    @Test
    fun `status codes match the data model`() {
        assertEquals(listOf("CRITICAL", "URGENT", "PLANNED"), Urgency.entries.map { it.code })
        assertEquals(listOf("OPEN", "FULFILLED", "CANCELLED", "EXPIRED", "REMOVED"), RequestStatus.entries.map { it.code })
        assertEquals(listOf("OFFERED", "CONFIRMED", "DECLINED", "WITHDRAWN", "DONATED"), ResponseStatus.entries.map { it.code })
    }
}
