package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureFactKind
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileScannerEvidenceTest {

    private fun result(
        username: String,
        url: String,
        exists: Boolean,
        verified: Boolean,
        provenance: String? = null,
        displayName: String? = username,
        bio: String? = null,
        links: List<String> = emptyList(),
        extractedText: String = "",
        profileImageUrl: String? = null,
        findingSourceUrl: String? = null
    ) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = username,
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.9f,
            providerId = "github"
        ),
        exists = exists,
        httpStatus = 200,
        displayName = displayName,
        bio = bio,
        profileImageUrl = profileImageUrl,
        links = links,
        extractedText = extractedText,
        findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "jane@example.com",
                sourceUrl = findingSourceUrl ?: url,
                evidenceSnippet = "contact",
                confidence = 0.9f,
                risk = RiskLevel.High,
                remediation = "remove"
            )
        ),
        confidenceSignals = listOf("ok"),
        verified = verified,
        verificationStatus = if (verified) "Verified" else "Exists",
        provenance = provenance
    )

    @Test
    fun emitsProfileEvidenceAndRelationships() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            emails = listOf("jane@example.com"),
            usernames = listOf("janedoe")
        )
        val results = listOf(result("janedoe", "https://github.com/janedoe", exists = true, verified = true))

        val collection = results.toEvidenceCollection(input)

        // Profile observation emitted natively (not via Finding adapter).
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Profile && it.value == "https://github.com/janedoe" })
        assertEquals(
            "github",
            collection.evidence.first { it.kind == EvidenceKind.Profile }.providerId
        )
        // PII finding bridged losslessly.
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Email && it.value == "jane@example.com" })
        // Scanner-asserted username↔profile relationship.
        assertTrue(collection.relationships.any { it.relation == "username_on_profile" && it.fromValue == "janedoe" })
        // Scanner-asserted PII-on-profile relationship.
        assertTrue(collection.relationships.any { it.relation == "mentions" && it.toValue == "jane@example.com" })
        // Identity seeds also present (self-contained collection).
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Username && it.value == "janedoe" })

        val profileEvidence = collection.evidence.single {
            it.kind == EvidenceKind.Profile && it.value == "https://github.com/janedoe"
        }
        val emailEvidence = collection.evidence.first {
            it.kind == EvidenceKind.Email &&
                it.value == "jane@example.com" &&
                it.sourceUrl == "https://github.com/janedoe"
        }
        assertEquals(
            listOf(profileEvidence.id),
            collection.relationships.single { it.relation == "username_on_profile" }.evidenceIds
        )
        assertEquals(
            listOf(emailEvidence.id),
            collection.relationships.single { it.relation == "mentions" }.evidenceIds
        )

        // The graph consumes the same stable IDs instead of relying on a
        // relationship description or endpoint-value inference.
        val graph = EntityGraphBuilder.build(
            input = input,
            evidence = collection.evidence,
            relationships = collection.relationships
        )
        val usernameProfileEdges = graph.edges.filter {
            it.relation == "username_on_profile" &&
                it.fromId == "username:janedoe" &&
                it.toId == "profile:https://github.com/janedoe"
        }
        assertTrue(usernameProfileEdges.isNotEmpty())
        assertTrue(usernameProfileEdges.all { profileEvidence.id in it.evidenceIds })
        val profileMentionEdges = graph.edges.filter {
            it.relation == "mentions" && it.fromId == "profile:https://github.com/janedoe"
        }
        assertTrue(profileMentionEdges.isNotEmpty())
        assertTrue(profileMentionEdges.all { emailEvidence.id in it.evidenceIds })
    }

    @Test
    fun emptyResultsStillEmitsIdentitySeeds() {
        // Self-contained collection: even with no profile results, identity seeds
        // are emitted so downstream consumers have the subject's own evidence.
        val input = IdentityInput(fullName = "Jane", usernames = listOf("jane"))
        val collection = listOf<ProfileScanResult>().toEvidenceCollection(input)
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Username && it.value == "jane" })
        assertEquals(0, collection.relationships.size)
    }

    @Test
    fun absentOrUnverifiedProfilesDoNotClaimDirectPublicReliability() {
        val input = IdentityInput(fullName = "Jane")
        val collection = listOf(
            result("missing", "https://example.test/missing", exists = false, verified = false),
            result(
                "unverified",
                "https://example.test/unverified",
                exists = true,
                verified = false,
                links = listOf("https://links.example.test/resume.pdf?download=1")
            )
        ).toEvidenceCollection(input, retrievedAtEpochMillis = 42_000L)

        val missing = collection.evidence.single { it.id == "profile:https://example.test/missing" }
        val unverified = collection.evidence.single { it.id == "profile:https://example.test/unverified" }
        assertEquals(EvidenceReliability.SearchEngineCandidate, missing.reliability)
        assertEquals(EvidenceState.Candidate, missing.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, unverified.reliability)
        assertEquals(EvidenceState.Observed, unverified.state)
        assertEquals(42_000L, missing.retrievedAtEpochMillis)
        val unverifiedLink = collection.evidence.single { it.value == "https://links.example.test/resume.pdf?download=1" }
        assertEquals(EvidenceState.Observed, unverifiedLink.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, unverifiedLink.reliability)
    }

    @Test
    fun profileProvenanceFlowsIntoProfileFindingAndLedgerFacts() {
        val path = "seed:name -> verified-profile"
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                provenance = path
            )
        ).toEvidenceCollection(input)

        val profile = collection.evidence.single { it.kind == EvidenceKind.Profile }
        val email = collection.evidence.single { it.kind == EvidenceKind.Email }
        assertEquals(listOf(path), profile.discoveryPath)
        assertEquals(listOf(path), email.discoveryPath)
    }

    @Test
    fun verifiedProfileDoesNotPromoteFindingFromAnotherSource() {
        val profileUrl = "https://github.com/janedoe"
        val collection = listOf(
            result(
                username = "janedoe",
                url = profileUrl,
                exists = true,
                verified = true,
                findingSourceUrl = "https://example.test/directory"
            )
        ).toEvidenceCollection(IdentityInput(fullName = "Jane Doe"))

        val email = collection.evidence.single {
            it.kind == EvidenceKind.Email && it.value == "jane@example.com"
        }
        assertEquals(EvidenceState.Observed, email.state)
        assertEquals(EvidenceReliability.Unknown, email.reliability)
        assertTrue(collection.relationships.none {
            it.relation == "mentions" &&
                it.fromValue == profileUrl &&
                it.toValue == "jane@example.com"
        })
    }

    @Test
    fun extractsVerifiedProfileFieldsAsTypedEvidence() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                displayName = "Jane Verified",
                bio = "Software Engineer",
                profileImageUrl = "https://example.com/avatar.jpg"
            )
        ).toEvidenceCollection(input)
        val nameEv = collection.evidence.find { it.kind == EvidenceKind.Username && it.value == "Jane Verified" }
        assertTrue(nameEv != null)
        assertTrue(nameEv?.state == EvidenceState.Verified)
        assertTrue(nameEv?.reliability == EvidenceReliability.DirectPublicProfile)
        val bioEv = collection.evidence.find { it.kind == EvidenceKind.SensitiveSnippet && it.value == "Software Engineer" }
        assertTrue(bioEv != null)

        val avatarEv = collection.evidence.find { it.kind == EvidenceKind.Image && it.value == "https://example.com/avatar.jpg" }
        assertTrue(avatarEv != null)
        assertEquals(EvidenceState.Verified, avatarEv?.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, avatarEv?.reliability)
        assertTrue(collection.relationships.any {
            it.relation == "uses_avatar" && avatarEv?.id in it.evidenceIds
        })
    }

    @Test
    fun profileFieldAndLinkEvidenceRetainExactObservedStrings() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                displayName = "  Jane  Verified  ",
                bio = "  Software   Engineer  ",
                profileImageUrl = "  https://example.com/avatar.jpg  ",
                links = listOf("  https://example.com/resume.pdf?download=1  ")
            )
        ).toEvidenceCollection(input)

        assertTrue(collection.evidence.any { it.value == "  Jane  Verified  " })
        assertTrue(collection.evidence.any { it.value == "  Software   Engineer  " })
        assertTrue(collection.evidence.any { it.value == "  https://example.com/avatar.jpg  " })
        val link = collection.evidence.single {
            it.kind == EvidenceKind.Document
        }
        assertEquals("  https://example.com/resume.pdf?download=1  ", link.value)
    }

    @Test
    fun extractsPublicLinksAsTypedEvidence() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                links = listOf(
                    "https://example.com/resume.pdf?download=1",
                    "https://web.archive.org/web/123/example.com?output=1",
                    "https://blog.com"
                )
            )
        ).toEvidenceCollection(input)
        val docEv = collection.evidence.find {
            it.kind == EvidenceKind.Document && it.value == "https://example.com/resume.pdf?download=1"
        }
        assertTrue(docEv != null)
        val archiveEv = collection.evidence.find {
            it.kind == EvidenceKind.Archive && it.value == "https://web.archive.org/web/123/example.com?output=1"
        }
        assertTrue(archiveEv != null)
        val urlEv = collection.evidence.find { it.kind == EvidenceKind.Url && it.value == "https://blog.com" }
        assertTrue(urlEv != null)
        val domainEv = collection.evidence.find { it.kind == EvidenceKind.Domain && it.value == "blog.com" }
        assertTrue(domainEv != null)

        val typed = TypedSeedEvidenceAdapter.admit(collection.evidence, input)
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Document && docEv?.id in it.evidenceIds })
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Archive && archiveEv?.id in it.evidenceIds })
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Domain && domainEv?.id in it.evidenceIds })
    }

    @Test
    fun archiveHostClassificationDoesNotUseSubstringMatches() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                links = listOf("https://notarchive.today.example.test/page")
            )
        ).toEvidenceCollection(input)

        assertEquals(EvidenceKind.Url, collection.evidence.single {
            it.value == "https://notarchive.today.example.test/page"
        }.kind)
    }

    @Test
    fun archivePhAndIsHostsClassifyAsArchivesWithoutSubstringMatches() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                links = listOf(
                    "https://archive.ph/abc123",
                    "https://foo.archive.ph/abc123",
                    "https://archive.is/def456",
                    "https://bar.archive.is/def456",
                    "https://notarchive.ph.example.test/page",
                    "https://notarchive.is.example.test/page"
                )
            )
        ).toEvidenceCollection(input)

        assertEquals(
            4,
            collection.evidence.count { it.kind == EvidenceKind.Archive }
        )
        assertTrue(collection.evidence.any {
            it.kind == EvidenceKind.Url && it.value.contains("notarchive.ph")
        })
        assertTrue(collection.evidence.any {
            it.kind == EvidenceKind.Url && it.value.contains("notarchive.is")
        })
    }

    @Test
    fun profileAttributeEvidenceIdsAreStableDigestsAndKeepExactValues() {
        val input = IdentityInput(fullName = "Jane Doe")
        val result = result(
            username = "janedoe",
            url = "https://github.com/janedoe",
            exists = true,
            verified = true,
            displayName = "Jane Verified"
        )
        val first = listOf(result).toEvidenceCollection(input)
        val second = listOf(result).toEvidenceCollection(input)
        val firstName = first.evidence.single { it.value == "Jane Verified" }
        val secondName = second.evidence.single { it.value == "Jane Verified" }
        assertEquals(firstName.id, secondName.id)
        assertTrue(firstName.id.startsWith("profile:display-name:"))
        assertTrue("Jane Verified" !in firstName.id)
        assertEquals("Jane Verified", firstName.value)
        assertEquals("https://github.com/janedoe", firstName.sourceUrl)
    }

    @Test
    fun extractsTextOnlyDocumentArchiveAndProfileLinksWithPunctuationTrimming() {
        val input = IdentityInput(fullName = "Jane Doe")
        val results = listOf(
            result(
                username = "janedoe",
                url = "https://profiles.example.test/janedoe",
                exists = true,
                verified = true,
                links = emptyList(),
                extractedText = """
                    Documents: (https://documents.example.test/resume.pdf).
                    Archive copy: [https://archive.today/web/2024/https://example.test].
                    Personal site: <https://homepage.example.test/about>!
                    See article: https://example.test/wiki/Title_(edition);
                    Historical mirror: https://archive.ph/snap123,
                """.trimIndent()
            )
        )

        val collection = results.toEvidenceCollection(input)

        val docEv = collection.evidence.single { it.kind == EvidenceKind.Document }
        assertEquals("https://documents.example.test/resume.pdf", docEv.value)
        assertEquals(EvidenceState.Verified, docEv.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, docEv.reliability)

        val archiveEvs = collection.evidence.filter { it.kind == EvidenceKind.Archive }
        assertEquals(2, archiveEvs.size)
        assertTrue(archiveEvs.any { it.value == "https://archive.today/web/2024/https://example.test" })
        assertTrue(archiveEvs.any { it.value == "https://archive.ph/snap123" })

        val urlEvs = collection.evidence.filter { it.kind == EvidenceKind.Url }
        assertEquals(2, urlEvs.size)
        assertTrue(urlEvs.any { it.value == "https://homepage.example.test/about" })
        assertTrue(urlEvs.any { it.value == "https://example.test/wiki/Title_(edition)" })

        val linksToValues = collection.relationships
            .filter { it.relation == "links_to" && it.fromValue == "https://profiles.example.test/janedoe" }
            .map { it.toValue }
            .toSet()
        assertTrue(docEv.value in linksToValues)
        assertTrue("https://archive.today/web/2024/https://example.test" in linksToValues)
        assertTrue("https://homepage.example.test/about" in linksToValues)
        assertTrue("https://example.test/wiki/Title_(edition)" in linksToValues)
        assertTrue("https://archive.ph/snap123" in linksToValues)
    }

    @Test
    fun textLinkEvidencePreservesExactValueSourceProvenanceAndConfidence() {
        val input = IdentityInput(fullName = "Jane Doe")
        val provenancePath = "seed:username -> candidate:profile -> verified"
        val exactUrl = "https://documents.example.test/papers/v1.0/spec.pdf?download=true#section2"
        val profileUrl = "https://profiles.example.test/janedoe"

        val res = result(
            username = "janedoe",
            url = profileUrl,
            exists = true,
            verified = true,
            provenance = provenancePath,
            links = emptyList(),
            extractedText = "Published paper: $exactUrl."
        )
        val collection1 = listOf(res).toEvidenceCollection(input)
        val collection2 = listOf(res).toEvidenceCollection(input)

        val doc1 = collection1.evidence.single { it.kind == EvidenceKind.Document }
        val doc2 = collection2.evidence.single { it.kind == EvidenceKind.Document }

        assertEquals(exactUrl, doc1.value)
        assertEquals(profileUrl, doc1.sourceUrl)
        assertEquals(listOf(provenancePath), doc1.discoveryPath)
        assertEquals(0.9f, doc1.confidence, 0.001f)
        assertEquals(EvidenceState.Verified, doc1.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, doc1.reliability)

        assertEquals(doc1.id, doc2.id)
        assertTrue(doc1.id.startsWith("profile:document:"))
        assertFalse(doc1.id.contains("documents.example.test"))
        assertFalse(doc1.id.contains("spec.pdf"))
    }

    @Test
    fun unverifiedAndCandidateProfileTextLinksDoNotPromoteState() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "candidate_user",
                url = "https://profiles.example.test/missing",
                exists = false,
                verified = false,
                links = emptyList(),
                extractedText = "Reference: https://documents.example.test/candidate.pdf."
            ),
            result(
                username = "observed_user",
                url = "https://profiles.example.test/unverified",
                exists = true,
                verified = false,
                links = emptyList(),
                extractedText = "Reference: https://documents.example.test/observed.pdf."
            )
        ).toEvidenceCollection(input)

        val candidateDoc = collection.evidence.single { it.value == "https://documents.example.test/candidate.pdf" }
        assertEquals(EvidenceState.Candidate, candidateDoc.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, candidateDoc.reliability)

        val observedDoc = collection.evidence.single { it.value == "https://documents.example.test/observed.pdf" }
        assertEquals(EvidenceState.Observed, observedDoc.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, observedDoc.reliability)
    }

    @Test
    fun textLinksEmitDomainEvidenceWithConsistentTypingAndDeduplication() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://profiles.example.test/janedoe",
                exists = true,
                verified = true,
                links = emptyList(),
                extractedText = """
                    First: https://documents.example.test/doc1.pdf
                    Second: https://documents.example.test/doc2.pdf
                    Archive: https://archive.today/page123
                """.trimIndent()
            )
        ).toEvidenceCollection(input)

        val domainEvs = collection.evidence.filter { it.kind == EvidenceKind.Domain }
        assertEquals(2, domainEvs.size)

        val docDomain = domainEvs.single { it.value == "documents.example.test" }
        assertEquals("https://profiles.example.test/janedoe", docDomain.sourceUrl)
        assertEquals(EvidenceState.Verified, docDomain.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, docDomain.reliability)

        val archiveDomain = domainEvs.single { it.value == "archive.today" }
        assertEquals("https://profiles.example.test/janedoe", archiveDomain.sourceUrl)

        val domainRelationships = collection.relationships.filter { it.relation == "links_to_domain" }
        assertTrue(domainRelationships.any { it.toValue == "documents.example.test" && docDomain.id in it.evidenceIds })
        assertTrue(domainRelationships.any { it.toValue == "archive.today" && archiveDomain.id in it.evidenceIds })
    }

    @Test
    fun rejectsInvalidMalformedAndNonHttpSchemesInExtractedText() {
        val input = IdentityInput(fullName = "Jane Doe")
        val overlongUrl = "https://example.test/" + "a".repeat(5000)
        val textWithDisallowedAndMalformed = """
            Ignored schemes:
            javascript:alert('xss')
            javascript:https://malicious.example.test/xss
            data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==
            data:https://evil.example.test/payload
            file:///etc/shadow
            file:https://files.example.test/secret
            content://media/external/images/media/1
            content:https://content.example.test/data
            ftp://files.example.test/manual.pdf
            mailto:jane@example.test

            Malformed HTTP URLs:
            https://
            http://
            https:///just/a/path
            https://example..test/doc.pdf
            https://example.test:99999/doc.pdf
            https://user:password@example.test/secret.pdf
            https://example.test/doc\u0000.pdf
            foohttps://example.test/doc.pdf
            $overlongUrl

            Valid URL:
            https://valid.example.test/verified-report.pdf.
        """.trimIndent()

        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://profiles.example.test/janedoe",
                exists = true,
                verified = true,
                links = emptyList(),
                extractedText = textWithDisallowedAndMalformed
            )
        ).toEvidenceCollection(input)

        val nonProfileEvidence = collection.evidence.filter {
            it.kind in setOf(EvidenceKind.Document, EvidenceKind.Archive, EvidenceKind.Url)
        }
        assertEquals(1, nonProfileEvidence.size)
        assertEquals("https://valid.example.test/verified-report.pdf", nonProfileEvidence.single().value)

        val domains = collection.evidence.filter { it.kind == EvidenceKind.Domain }.map { it.value }
        assertEquals(listOf("valid.example.test"), domains)
    }

    @Test
    fun extractedUrlDedupKeepsCaseSensitivePathAndQueryValuesDistinct() {
        val extracted = extractAbsoluteHttpUrlsFromText(
            "https://example.test/Case.pdf?Download=True " +
                "https://EXAMPLE.TEST/case.pdf?download=true " +
                "HTTPS://EXAMPLE.TEST/Case.pdf?Download=True"
        )

        assertEquals(
            listOf(
                "https://example.test/Case.pdf?Download=True",
                "https://EXAMPLE.TEST/case.pdf?download=true"
            ),
            extracted
        )
    }

    @Test
    fun textLinkExtractionDeduplicatesAgainstDirectLinksAndEnforcesBounds() {
        val input = IdentityInput(fullName = "Jane Doe")

        // 1. Deduplication against direct links and within text
        val dedupResult = result(
            username = "janedoe",
            url = "https://profiles.example.test/janedoe",
            exists = true,
            verified = true,
            links = listOf("https://example.test/direct.pdf"),
            extractedText = """
                Same as direct: https://example.test/direct.pdf
                Same case insensitive scheme/host: HTTPS://EXAMPLE.TEST/direct.pdf
                New link: https://example.test/from-text.pdf
                Duplicate in text: https://example.test/from-text.pdf
            """.trimIndent()
        )
        val dedupCollection = listOf(dedupResult).toEvidenceCollection(input)
        val docValues = dedupCollection.evidence.filter { it.kind == EvidenceKind.Document }.map { it.value }
        assertEquals(2, docValues.size)
        assertTrue("https://example.test/direct.pdf" in docValues)
        assertTrue("https://example.test/from-text.pdf" in docValues)

        // 2. Per-profile bound
        val overLimitUrls = (0 until (MAX_TEXT_LINKS_PER_PROFILE + 15))
            .joinToString(" ") { "https://bounded.example.test/doc_$it.pdf" }
        val boundedResult = result(
            username = "janedoe",
            url = "https://profiles.example.test/bounded",
            exists = true,
            verified = true,
            links = emptyList(),
            extractedText = overLimitUrls
        )
        val boundedCollection = listOf(boundedResult).toEvidenceCollection(input)
        val boundedDocs = boundedCollection.evidence.filter { it.kind == EvidenceKind.Document }
        assertEquals(MAX_TEXT_LINKS_PER_PROFILE, boundedDocs.size)

        // 3. Total bound across profiles
        val manyProfiles = (0 until 6).map { profileIdx ->
            val profileUrls = (0 until 50)
                .joinToString(" ") { "https://total-bounded.example.test/p${profileIdx}_doc_$it.pdf" }
            result(
                username = "user_$profileIdx",
                url = "https://profiles.example.test/user_$profileIdx",
                exists = true,
                verified = true,
                links = emptyList(),
                extractedText = profileUrls
            )
        }
        val totalCollection = manyProfiles.toEvidenceCollection(input)
        val totalDocs = totalCollection.evidence.filter { it.kind == EvidenceKind.Document }
        assertEquals(MAX_TOTAL_TEXT_LINKS, totalDocs.size)
    }

    @Test
    fun typedSeedAdmissionAndLedgerSeeDocumentArchiveAndUrlEvidenceFromTextLinks() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://profiles.example.test/janedoe",
                exists = true,
                verified = true,
                links = emptyList(),
                extractedText = """
                    Resume: https://documents.example.test/cv.pdf
                    Wayback: https://web.archive.org/web/20230101/https://example.test
                    Blog: https://site.example.test/blog
                """.trimIndent()
            )
        ).toEvidenceCollection(input)

        val docEv = collection.evidence.single { it.kind == EvidenceKind.Document }
        val archiveEv = collection.evidence.single { it.kind == EvidenceKind.Archive }
        val urlEv = collection.evidence.single { it.kind == EvidenceKind.Url }
        val domainEvs = collection.evidence.filter { it.kind == EvidenceKind.Domain }

        // Typed seed admission
        val typedModel = TypedSeedEvidenceAdapter.admit(collection.evidence, input)
        assertTrue(typedModel.admittedSeeds.any {
            it.kind == TypedSeedKind.Document && docEv.id in it.evidenceIds && it.exactValue == docEv.value
        })
        assertTrue(typedModel.admittedSeeds.any {
            it.kind == TypedSeedKind.Archive && archiveEv.id in it.evidenceIds && it.exactValue == archiveEv.value
        })
        assertTrue(typedModel.admittedSeeds.any {
            it.kind == TypedSeedKind.Url && urlEv.id in it.evidenceIds && it.exactValue == urlEv.value
        })
        assertTrue(typedModel.admittedSeeds.any {
            it.kind == TypedSeedKind.Domain && domainEvs.any { d -> d.id in it.evidenceIds }
        })

        // Canonical Exposure Ledger
        val ledger = collection.toExposureLedger()
        assertTrue(ledger.facts.any {
            it.kind == ExposureFactKind.Document && docEv.id in it.evidenceIds && it.exactValue == docEv.value
        })
        assertTrue(ledger.facts.any {
            it.kind == ExposureFactKind.Archive && archiveEv.id in it.evidenceIds && it.exactValue == archiveEv.value
        })
        assertTrue(ledger.facts.any {
            it.kind == ExposureFactKind.Website && urlEv.id in it.evidenceIds && it.exactValue == urlEv.value
        })
        assertTrue(ledger.facts.any {
            it.kind == ExposureFactKind.Domain && domainEvs.any { d -> d.id in it.evidenceIds }
        })
    }
}
