package io.dossier.app.domain.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsernameSimilarityContributorTest {

    private val contributor = UsernameSimilarityContributor()

    @Test
    fun exactVariantScoresHigh() {
        val a = Evidence(id = "1", kind = EvidenceKind.Username, value = "palaashatri")
        val b = Evidence(id = "2", kind = EvidenceKind.Username, value = "palaash.atri")
        val signals = contributor.contribute(a, b)
        assertEquals(0.85f, signals!!.score, 1e-6f)
        assertEquals(true, signals.reasons.any { it.contains("separators") })
    }

    @Test
    fun substringScoresMedium() {
        val a = Evidence(id = "1", kind = EvidenceKind.Username, value = "palaash")
        val b = Evidence(id = "2", kind = EvidenceKind.Username, value = "palaash_atri")
        val signals = contributor.contribute(a, b)
        assertEquals(0.6f, signals!!.score, 1e-6f)
    }

    @Test
    fun unrelatedUsernamesReturnNull() {
        val a = Evidence(id = "1", kind = EvidenceKind.Username, value = "alice")
        val b = Evidence(id = "2", kind = EvidenceKind.Username, value = "bobsmith")
        assertNull(contributor.contribute(a, b))
    }

    @Test
    fun nonUsernameEvidenceReturnsNull() {
        val a = Evidence(id = "1", kind = EvidenceKind.Email, value = "a@b.com")
        val b = Evidence(id = "2", kind = EvidenceKind.Username, value = "bob")
        assertNull(contributor.contribute(a, b))
    }
}
