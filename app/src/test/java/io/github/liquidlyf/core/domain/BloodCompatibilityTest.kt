package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodCompatibilityTest {

    private val shared = SharedVectors.read("blood-compatibility.json")

    @Test
    fun `groups and components use the shared codes in the shared order`() {
        assertEquals(
            shared.getValue("groups").jsonArray.map { it.jsonPrimitive.content },
            BloodGroup.entries.map { it.code },
        )
        assertEquals(
            shared.getValue("components").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            BloodComponent.entries.map { it.code }.toSet(),
        )
    }

    @Test
    fun `every patient and component matches the shared table (the 8x8 matrix x 4 components)`() {
        val table = shared.getValue("compatibleDonors").jsonObject
        var checked = 0
        for (component in BloodComponent.entries) {
            val rows = table.getValue(component.code).jsonObject
            for (patient in BloodGroup.entries) {
                val expected = rows.getValue(patient.code).jsonArray
                    .map { BloodGroup.fromCode(it.jsonPrimitive.content)!! }.toSet()
                assertEquals("$component for $patient", expected, BloodCompatibility.compatibleDonors(patient, component).toSet())
                for (donor in BloodGroup.entries) {
                    assertEquals(
                        "$component: $donor to $patient",
                        donor in expected,
                        BloodCompatibility.canReceive(patient, donor, component),
                    )
                    checked++
                }
            }
        }
        assertEquals(8 * 8 * 4, checked)
    }

    @Test
    fun `O negative gives red cells to everyone and AB positive receives from everyone`() {
        BloodGroup.entries.forEach {
            assertTrue(BloodCompatibility.canReceive(it, BloodGroup.O_NEG, BloodComponent.RED_CELLS))
        }
        assertEquals(BloodGroup.entries, BloodCompatibility.compatibleDonors(BloodGroup.AB_POS, BloodComponent.WHOLE_BLOOD))
        assertFalse(BloodCompatibility.canReceive(BloodGroup.O_POS, BloodGroup.A_POS, BloodComponent.WHOLE_BLOOD))
    }

    @Test
    fun `accepted groups are exact by default and the full compatible list on opt-in`() {
        for (component in BloodComponent.entries) {
            for (patient in BloodGroup.entries) {
                assertEquals(listOf(patient), BloodCompatibility.acceptedGroups(patient, component, acceptCompatible = false))
                val compatible = BloodCompatibility.acceptedGroups(patient, component, acceptCompatible = true)
                // What firestore.rules acceptedGroupsOk needs: contains the patient, no duplicates,
                // all compatible.
                assertTrue(patient in compatible)
                assertEquals(compatible.size, compatible.toSet().size)
                assertEquals(BloodCompatibility.compatibleDonors(patient, component), compatible)
            }
        }
    }
}
