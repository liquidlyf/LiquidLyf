package io.github.liquidlyf.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopLevelDestinationTest {

    @Test
    fun `tabs are in the order of the bottom bar`() {
        assertEquals(
            listOf(TopLevelDestination.HOME, TopLevelDestination.REQUESTS, TopLevelDestination.DONORS, TopLevelDestination.PROFILE),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun `fromName finds saved tabs and ignores unknown ones`() {
        TopLevelDestination.entries.forEach { assertEquals(it, TopLevelDestination.fromName(it.name)) }
        assertNull(TopLevelDestination.fromName("SETTINGS"))
        assertNull(TopLevelDestination.fromName(null))
    }

    @Test
    fun `each tab has its own graph and root`() {
        val graphs = TopLevelDestination.entries.map { it.graph }
        val roots = TopLevelDestination.entries.map { it.root }
        assertEquals(graphs.size, graphs.toSet().size)
        assertEquals(roots.size, roots.toSet().size)
        assertEquals(Home, TopLevelDestination.HOME.root)
    }
}
