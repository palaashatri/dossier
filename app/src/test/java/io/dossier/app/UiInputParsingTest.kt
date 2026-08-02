package io.dossier.app

import io.dossier.app.ui.screens.parsePasswordsExactly
import io.dossier.app.ui.screens.parseSignalList
import org.junit.Assert.assertEquals
import org.junit.Test

class UiInputParsingTest {

    @Test
    fun passwordParsingPreservesMeaningfulWhitespace() {
        assertEquals(
            listOf(" leading", "trailing ", " both ", "plain"),
            parsePasswordsExactly(" leading\ntrailing \n both \nplain\n")
        )
    }

    @Test
    fun signalParsingAcceptsLinesAndCommasWithoutDuplicates() {
        assertEquals(
            listOf("alpha", "beta", "Gamma"),
            parseSignalList(" alpha, beta\nGamma\nALPHA ")
        )
    }
}
