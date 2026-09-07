package io.dossier.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEvaluationHarnessTest {
    @Test
    fun syntheticCorpusCoversEvidenceContradictionUnsupportedClaimAndFallback() {
        val report = AiProductionEvaluation.evaluate(AiProductionEvaluation.syntheticCorpus())

        assertEquals(AiEvaluationCorpusKind.SYNTHETIC, report.corpus.kind)
        assertEquals(AiProductionEvaluation.CORPUS_VERSION, report.corpus.version)
        assertEquals(5, report.metrics.total)
        assertEquals(2, report.metrics.acceptedCases)
        assertEquals(2, report.metrics.rejectedCases)
        assertEquals(1, report.metrics.fallbackCases)
        assertEquals(1, report.metrics.unknownEvidenceRejections)
        assertEquals(1, report.metrics.unsupportedIdentifierRejections)
        assertEquals(1, report.metrics.contradictionDowngrades)
        assertEquals(1, report.metrics.fallbackOutputsProduced)
        assertEquals(5, report.metrics.passedCases)
        assertTrue(report.metrics.allCasesPassed)
    }
}
