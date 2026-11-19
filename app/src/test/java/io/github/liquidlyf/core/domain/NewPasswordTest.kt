package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.domain.FieldCheck.ERROR
import io.github.liquidlyf.core.domain.FieldCheck.NONE
import io.github.liquidlyf.core.domain.FieldCheck.OK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D24, the maintainer's picks (19 Nov 2026): when each box is grey, green or red. */
class NewPasswordTest {
    private val first = "rokto2026"

    @Test
    fun `the first box shows the rule and a count while short, never red while typing`() {
        var p = NewPassword()
        assertEquals(NONE, p.valueCheck)
        assertNull(p.count)
        p = p.focusValue(true).type("rokto")
        assertEquals(NONE, p.valueCheck)
        assertEquals(5, p.count)
        p = p.type(first)
        assertEquals(OK, p.valueCheck)
        assertNull(p.count)
    }

    @Test
    fun `the first box turns red when left with 1 to 7 characters, and stays red until 8`() {
        var p = NewPassword().focusValue(true).type("rokto").focusValue(false)
        assertEquals(ERROR, p.valueCheck)
        assertEquals(5, p.count) // the count stays
        p = p.focusValue(true).type("rokto20") // fixing it
        assertEquals(ERROR, p.valueCheck)
        p = p.type("rokto202")
        assertEquals(OK, p.valueCheck)
        // From 8 back to 7 while typing: grey again, until it's left short again.
        p = p.type("rokto20")
        assertEquals(NONE, p.valueCheck)
    }

    @Test
    fun `leaving the first box empty isn't red, but the button is`() {
        var p = NewPassword().focusValue(true).focusValue(false)
        assertEquals(NONE, p.valueCheck)
        p = p.check()
        assertEquals(ERROR, p.valueCheck)
        assertEquals(NONE, p.againCheck) // both empty: they don't differ
    }

    @Test
    fun `the second box has no colour while it's a correct start of the first`() {
        var p = NewPassword(value = first).focusAgain(true)
        assertEquals(NONE, p.againCheck)
        p = p.typeAgain("rokto")
        assertEquals(NONE, p.againCheck)
        p = p.typeAgain(first)
        assertEquals(OK, p.againCheck)
        assertTrue(p.isValid)
    }

    @Test
    fun `a wrong letter in the second box is red at once, and fixing it turns it green`() {
        var p = NewPassword(value = first).focusAgain(true).typeAgain("rokto2O")
        assertEquals(ERROR, p.againCheck)
        p = p.typeAgain("rokto2") // back to a correct start, still typing
        assertEquals(NONE, p.againCheck)
        p = p.typeAgain(first)
        assertEquals(OK, p.againCheck)
        // Longer than the first is wrong too.
        assertEquals(ERROR, p.typeAgain("${first}x").againCheck)
    }

    @Test
    fun `leaving the second box unfinished is red, coming back to finish it isn't`() {
        var p = NewPassword(value = first).focusAgain(true).typeAgain("rokto").focusAgain(false)
        assertEquals(ERROR, p.againCheck)
        p = p.focusAgain(true)
        assertEquals(NONE, p.againCheck)
    }

    @Test
    fun `after the button the second box stays red until they match, an empty one too`() {
        var p = NewPassword(value = first).check()
        assertEquals(ERROR, p.againCheck)
        p = p.focusAgain(true).typeAgain("rokto")
        assertEquals(ERROR, p.againCheck)
        p = p.typeAgain(first)
        assertEquals(OK, p.againCheck)
        assertFalse(p.differShown)
        // Editing the first box to match the second clears it too.
        p = NewPassword(value = "rokto2027", again = first).check()
        assertEquals(ERROR, p.againCheck)
        assertEquals(OK, p.type(first).againCheck)
    }

    @Test
    fun `the same short password twice isn't green, the first box says why`() {
        val p = NewPassword(value = "rokto", again = "rokto").check()
        assertEquals(NONE, p.againCheck)
        assertEquals(ERROR, p.valueCheck)
        assertFalse(p.isValid)
    }

    @Test
    fun `editing the first box after a match, the second box is red while it's not being typed in`() {
        val p = NewPassword(value = first, again = first).type("${first}7")
        assertEquals(ERROR, p.againCheck)
        assertEquals(NONE, p.focusAgain(true).againCheck)
    }
}
