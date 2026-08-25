package io.dossier.app.domain.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalInteractionImportPluginTest {
    @Test
    fun acceptedRowsHaveStableOpaqueCandidateEvidenceAndMergedRelationshipProvenance() {
        val raw = """source,target,relation,text
            alice,bob,mention,public post one
            alice,bob,mention,public post two
        """.trimIndent()
        val plugin = ExternalInteractionImportPlugin()

        val first = plugin.parseImport(raw, "a".repeat(64), "interactions.csv", setOf("@alice"))
        val second = plugin.parseImport(raw, "a".repeat(64), "interactions.csv", setOf("alice"))

        assertEquals(first.evidence.map { it.id }, second.evidence.map { it.id })
        assertEquals(2, first.evidence.size)
        assertTrue(first.evidence.all { it.state == EvidenceState.Candidate })
        assertTrue(first.evidence.all { it.reliability == EvidenceReliability.ThirdPartyAggregation })
        assertEquals(1, first.relationships.size)
        assertEquals(
            first.evidence.mapTo(mutableSetOf(), Evidence::id),
            first.relationships.single().evidenceIds.toSet()
        )
        assertFalse(first.evidence.any { evidence ->
            evidence.id.contains("alice", ignoreCase = true) ||
                evidence.id.contains("bob", ignoreCase = true) ||
                evidence.id.contains("interactions.csv", ignoreCase = true)
        })
    }

    @Test
    fun rowsContainingCredentialFieldsProduceNoEvidenceOrRelationships() {
        val raw = """source,target,relation,text,password
            alice,bob,mention,public post,password=do-not-import
        """.trimIndent()

        val result = ExternalInteractionImportPlugin().parseImport(
            rawText = raw,
            importDigest = "b".repeat(64),
            displayName = "unsafe.csv",
            authorizedHandles = setOf("alice")
        )

        assertTrue(result.evidence.isEmpty())
        assertTrue(result.relationships.isEmpty())
    }
}
