package io.dossier.app.data.web

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumverifyReportParserTest {
    @Test
    fun explicitPhoneResponseBecomesMaskedCandidateMetadata() {
        val input = IdentityInput(fullName = "", phones = listOf("+91 98765 43210"))
        val raw = """{
          "valid": true,
          "number": "919876543210",
          "country_code": "IN",
          "country_name": "India",
          "location": "Delhi",
          "carrier": "Example Carrier",
          "line_type": "mobile"
        }""".trimIndent()

        assertTrue(NumverifyReportParser.looksLikeNumverify("numverify-response.json", raw))
        val result = NumverifyReportParser.parse(raw, input)
        assertEquals(1, result.evidence.size)
        val evidence = result.evidence.single()
        assertEquals(EvidenceKind.Phone, evidence.kind)
        assertEquals(EvidenceState.Candidate, evidence.state)
        assertEquals("••••3210", evidence.value)
        assertTrue(evidence.snippet.orEmpty().contains("carrier=Example Carrier"))
        assertFalse(evidence.signals.any { it.contains("subscriber identity is verified", true) })
    }

    @Test
    fun responseForDifferentPhoneIsRejected() {
        val input = IdentityInput(fullName = "", phones = listOf("+91 98765 43210"))
        val raw = """{"valid":true,"number":"14155552671","country_code":"US","carrier":"Other"}"""
        assertTrue(NumverifyReportParser.parse(raw, input).evidence.isEmpty())
    }
}
