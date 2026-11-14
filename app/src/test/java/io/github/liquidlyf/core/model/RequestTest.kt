package io.github.liquidlyf.core.model

import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestTest {

    @Test
    fun `live offers leave out withdrawn and declined ones`() {
        val r = TestData.request().copy(
            responseCount = 4,
            responders = ResponseStatus.entries.associate { s -> "donor-${s.name}" to Responder("Donor", BloodGroup.B_POS, s) },
        )
        assertEquals(3, r.liveOffers) // offered, confirmed, donated
        assertEquals(0, TestData.request().liveOffers)
    }
}
