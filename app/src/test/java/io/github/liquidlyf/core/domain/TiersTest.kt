package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TiersTest {

    private val vectors = SharedVectors.read("tier-vectors.json")

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun requestOf(json: JsonObject) = TierRequest(
        bloodGroup = BloodGroup.fromCode(json.getValue("bloodGroup").jsonPrimitive.content)!!,
        component = BloodComponent.fromCode(json.getValue("component").jsonPrimitive.content)!!,
        acceptCompatible = json.getValue("acceptCompatible").jsonPrimitive.boolean,
        acceptedGroups = json.strings("acceptedGroups").map { BloodGroup.fromCode(it)!! },
        division = json.getValue("division").jsonPrimitive.content,
        district = json.getValue("district").jsonPrimitive.content,
        upazila = (json["upazila"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )

    @Test
    fun `every shared vector produces exactly the Worker's conditions`() {
        assertEquals(Tiers.MAX_TOPICS_PER_CONDITION, vectors.getValue("maxTopicsPerCondition").jsonPrimitive.int)
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val request = requestOf(case.getValue("request").jsonObject)
            val neighbours = case.strings("neighbours")
            val expected = case.getValue("tiers").jsonArray.map { it.jsonObject }
                .associate { it.getValue("tier").jsonPrimitive.int to it.strings("conditions") }

            for (tier in 1..Tiers.LAST_TIER) {
                assertEquals("$name, tier $tier", expected[tier] ?: emptyList<String>(), Tiers.tierConditions(request, tier, neighbours))
            }
            // Escalation walks exactly the listed tiers, in order, then stops.
            val walked = generateSequence(Tiers.firstTier(request, neighbours)) { Tiers.nextTier(request, it, neighbours) }.toList()
            assertEquals(name, expected.keys.toList(), walked)
        }
    }

    @Test
    fun `no condition ever has more than 5 topics`() {
        val everyone = TierRequest(
            BloodGroup.AB_POS, BloodComponent.WHOLE_BLOOD, true, BloodGroup.entries, "V1", "D1", "U1",
        )
        val conditions = Tiers.tierConditions(everyone, 4, listOf("D2", "D3", "D4"))
        assertTrue(conditions.isNotEmpty())
        conditions.forEach { assertTrue(it, it.split(" || ").size <= Tiers.MAX_TOPICS_PER_CONDITION) }
    }

    @Test
    fun `groups outside the compatibility table are never sent to`() {
        // A tampered acceptedGroups can't widen the fan-out: B+ red cells never reach A donors.
        val r = TierRequest(BloodGroup.B_POS, BloodComponent.RED_CELLS, true, listOf(BloodGroup.A_POS, BloodGroup.B_POS), "V1", "D1", "U1")
        assertEquals(emptyList<String>(), Tiers.tierTopics(r, 2))
    }

    @Test
    fun `the own district is not repeated as a neighbour`() {
        val r = TierRequest(BloodGroup.B_POS, BloodComponent.WHOLE_BLOOD, false, listOf(BloodGroup.B_POS), "V1", "D1", "U1")
        assertEquals(listOf("di_D2_B_POS"), Tiers.tierTopics(r, 4, listOf("D1", "D2")))
        assertEquals(emptyList<String>(), Tiers.tierTopics(r, 5))
        assertNull(Tiers.nextTier(r, 4))
    }

    @Test
    fun `donors subscribe to their upazila, district and division topics`() {
        assertEquals(
            setOf("up_dhaka-dhanmondi_O_NEG", "di_dhaka_O_NEG", "dv_dhaka-div_O_NEG"),
            Tiers.donorTopics(BloodGroup.O_NEG, division = "dhaka-div", district = "dhaka", upazila = "dhaka-dhanmondi"),
        )
        assertEquals("usr_abc123XYZ", Tiers.userTopic("abc123XYZ"))
    }

    @Test
    fun `unsafe area codes and uids are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Tiers.topic(Tiers.Level.DISTRICT, "D1' in topics || 'x", BloodGroup.A_POS)
        }
        assertThrows(IllegalArgumentException::class.java) { Tiers.topic(Tiers.Level.UPAZILA, "", BloodGroup.A_POS) }
        assertThrows(IllegalArgumentException::class.java) { Tiers.userTopic("a/b") }
    }
}
