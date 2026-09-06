package io.dossier.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseImageLookupScreenTest {
    @Test
    fun delayedOlderLookupCannotPublishAfterNewerRequest() {
        val gate = ReverseMediaLookupRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        val visible = mutableListOf<String>()

        // Simulate A completing after B has already started. The same guard is
        // used by result, error, and spinner updates in the Compose screen.
        if (gate.isCurrent(first)) visible += "A"
        if (gate.isCurrent(second)) visible += "B"

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertEquals(listOf("B"), visible)
    }
}
