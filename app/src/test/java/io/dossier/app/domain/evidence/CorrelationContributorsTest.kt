package io.dossier.app.domain.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrelationContributorsTest {

    @Test
    fun emailDomainMatches() {
        val a = Evidence(id = "1", kind = EvidenceKind.Email, value = "alice@acme.com")
        val b = Evidence(id = "2", kind = EvidenceKind.Email, value = "bob@acme.com")
        val s = EmailDomainContributor().contribute(a, b)
        assertEquals(0.7f, s!!.score, 1e-6f)
        assertEquals(true, s.reasons.any { it.contains("acme.com") })
    }

    @Test
    fun emailDomainDifferentReturnsNull() {
        val a = Evidence(id = "1", kind = EvidenceKind.Email, value = "alice@acme.com")
        val b = Evidence(id = "2", kind = EvidenceKind.Email, value = "bob@other.org")
        assertNull(EmailDomainContributor().contribute(a, b))
    }

    @Test
    fun sharedIdentifierFindsUsernameInEmail() {
        val user = Evidence(id = "1", kind = EvidenceKind.Username, value = "janedoe")
        val mail = Evidence(id = "2", kind = EvidenceKind.Email, value = "janedoe@gmail.com")
        val s = SharedIdentifierContributor(setOf("janedoe")).contribute(user, mail)
        assertEquals(0.65f, s!!.score, 1e-6f)
    }

    @Test
    fun sharedIdentifierNoSeedsReturnsNull() {
        val user = Evidence(id = "1", kind = EvidenceKind.Username, value = "janedoe")
        val mail = Evidence(id = "2", kind = EvidenceKind.Email, value = "x@gmail.com")
        assertNull(SharedIdentifierContributor().contribute(user, mail))
    }

    @Test
    fun sharedDomainMatchesProfileLinks() {
        val a = Evidence(id = "1", kind = EvidenceKind.Profile, value = "https://github.com/janedoe")
        val b = Evidence(id = "2", kind = EvidenceKind.PublicSearchEvidence, value = "http://www.github.com/about")
        val s = SharedDomainContributor().contribute(a, b)
        assertEquals(0.6f, s!!.score, 1e-6f)
        assertEquals(true, s.reasons.any { it.contains("github.com") })
    }

    @Test
    fun sharedDomainDifferentReturnsNull() {
        val a = Evidence(id = "1", kind = EvidenceKind.Profile, value = "https://github.com/x")
        val b = Evidence(id = "2", kind = EvidenceKind.PublicSearchEvidence, value = "https://gitlab.com/x")
        assertNull(SharedDomainContributor().contribute(a, b))
    }
}
