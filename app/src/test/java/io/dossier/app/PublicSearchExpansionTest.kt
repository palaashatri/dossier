package io.dossier.app

import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSearchExpansionTest {

    private fun verifiedResult(
        username: String = "verified_user",
        url: String = "https://github.test/verified_user",
        exists: Boolean = true,
        verified: Boolean = true,
        providerVerificationState: ProviderVerificationState? = ProviderVerificationState.Present,
        provenance: String? = "verified-profile",
        verificationStatus: String? = "Directly verified",
        findings: List<Finding> = emptyList()
    ): ProfileScanResult = ProfileScanResult(
        candidate = UsernameCandidate(
            username = username,
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.95f,
            providerId = "github"
        ),
        exists = exists,
        httpStatus = 200,
        displayName = username,
        bio = "Verified user account",
        profileImageUrl = null,
        links = listOf(url),
        extractedText = "Verified user profile",
        findings = findings,
        confidenceSignals = listOf("Direct verified profile"),
        verified = verified,
        verificationStatus = verificationStatus,
        provenance = provenance,
        providerVerificationState = providerVerificationState
    )

    @Test
    fun verifiedSameSourceAcceptance_acceptsHandleEmailAndPhone() {
        val profileUrl = "https://github.test/janedoe"
        val findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "jane.verified@example.test",
                sourceUrl = profileUrl,
                evidenceSnippet = "Contact: jane.verified@example.test",
                confidence = 0.92f,
                risk = RiskLevel.High,
                remediation = "Review email exposure"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+1 (555) 010-9999",
                sourceUrl = profileUrl,
                evidenceSnippet = "Phone: +1 (555) 010-9999",
                confidence = 0.88f,
                risk = RiskLevel.High,
                remediation = "Review phone exposure"
            )
        )
        val result = verifiedResult(
            username = "janedoe_verified",
            url = profileUrl,
            findings = findings
        )
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            emails = listOf("jane.orig@example.test"),
            phones = listOf("+1 555 010 0000")
        )

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result))

        assertEquals(listOf("jane.verified@example.test"), discovered.emails)
        assertEquals(listOf("15550109999"), discovered.phones)
        assertEquals(listOf("janedoe_verified"), discovered.handles)
    }

    @Test
    fun rejection_unverifiedProfileCannotContributeHandleOrFindings() {
        val profileUrl = "https://github.test/candidate_user"
        val findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "unverified@example.test",
                sourceUrl = profileUrl,
                evidenceSnippet = "email on unverified page",
                confidence = 0.95f,
                risk = RiskLevel.High,
                remediation = "remediate"
            )
        )
        val unverified = verifiedResult(
            username = "candidate_user",
            url = profileUrl,
            exists = true,
            verified = false,
            verificationStatus = "Candidate lead only",
            findings = findings
        )
        val nonExistent = verifiedResult(
            username = "ghost_user",
            url = "https://github.test/ghost_user",
            exists = false,
            verified = true,
            findings = findings
        )
        val input = IdentityInput(fullName = "Test User")

        val discoveredUnverified = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(unverified))
        assertTrue("Unverified profile must not contribute terms", discoveredUnverified.isEmpty)

        val discoveredNonExistent = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(nonExistent))
        assertTrue("Non-existent profile must not contribute terms", discoveredNonExistent.isEmpty)
    }

    @Test
    fun rejection_lowConfidenceFindingsRejected() {
        val profileUrl = "https://github.test/janedoe"
        val findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "lowconf@example.test",
                sourceUrl = profileUrl,
                evidenceSnippet = "low confidence email",
                confidence = 0.79f, // Below 0.80
                risk = RiskLevel.Low,
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+15550101111",
                sourceUrl = profileUrl,
                evidenceSnippet = "low confidence phone",
                confidence = 0.70f, // Below 0.80
                risk = RiskLevel.Low,
                remediation = "remediate"
            )
        )
        val result = verifiedResult(
            username = "janedoe_new",
            url = profileUrl,
            findings = findings
        )
        val input = IdentityInput(fullName = "Jane Doe")

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result))

        assertTrue("Low-confidence emails must be rejected", discovered.emails.isEmpty())
        assertTrue("Low-confidence phones must be rejected", discovered.phones.isEmpty())
        assertEquals("Handle from verified profile should still be accepted", listOf("janedoe_new"), discovered.handles)
    }

    @Test
    fun rejection_offSourceFindingsRejected() {
        val profileUrl = "https://github.test/janedoe"
        val findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "offsource@example.test",
                sourceUrl = "https://other-site.test/directory",
                evidenceSnippet = "off-source match",
                confidence = 0.95f,
                risk = RiskLevel.High,
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+15550102222",
                sourceUrl = "https://github.test/someone_else",
                evidenceSnippet = "different profile match",
                confidence = 0.95f,
                risk = RiskLevel.High,
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Email,
                value = "nullsource@example.test",
                sourceUrl = null,
                evidenceSnippet = "null source match",
                confidence = 0.95f,
                risk = RiskLevel.High,
                remediation = "remediate"
            )
        )
        val result = verifiedResult(
            username = "janedoe_new",
            url = profileUrl,
            findings = findings
        )
        val input = IdentityInput(fullName = "Jane Doe")

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result))

        assertTrue("Off-source emails must be rejected", discovered.emails.isEmpty())
        assertTrue("Off-source phones must be rejected", discovered.phones.isEmpty())
    }

    @Test
    fun rejection_breachDerivedImportAndAmbiguousFindingsRejected() {
        val profileUrl = "https://github.test/janedoe"

        // Breach-derived result provenance
        val breachResult = verifiedResult(
            username = "breach_handle",
            url = profileUrl,
            provenance = "breach-index:leak-dump",
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "leak@example.test",
                    sourceUrl = profileUrl,
                    confidence = 0.95f,
                    risk = RiskLevel.Critical,
                    evidenceSnippet = "breach dump record",
                    remediation = "remediate"
                )
            )
        )
        val input = IdentityInput(fullName = "Jane Doe")
        val discoveredBreach = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(breachResult))
        assertTrue("Breach-derived result must be rejected", discoveredBreach.isEmpty)

        // Breach / import / leak finding snippets
        val findingSnippetsResult = verifiedResult(
            username = "safe_user",
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "pwned@example.test",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.Critical,
                    evidenceSnippet = "compromised stealer log credentials",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "+15550103333",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.Critical,
                    evidenceSnippet = "thirdparty import record",
                    remediation = "remediate"
                )
            )
        )
        val discoveredSnippets = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(findingSnippetsResult))
        assertTrue("Compromised/import finding emails must be rejected", discoveredSnippets.emails.isEmpty())
        assertTrue("Compromised/import finding phones must be rejected", discoveredSnippets.phones.isEmpty())

        // Ambiguous values
        val ambiguousValuesResult = verifiedResult(
            username = "unknown", // Ambiguous placeholder
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "noreply@example.test", // Generic role account
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.Low,
                    evidenceSnippet = "automated sender",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "00000000", // Trivial repeating digits
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.Low,
                    evidenceSnippet = "placeholder phone",
                    remediation = "remediate"
                )
            )
        )
        val discoveredAmbiguous = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(ambiguousValuesResult))
        assertTrue("Ambiguous email must be rejected", discoveredAmbiguous.emails.isEmpty())
        assertTrue("Ambiguous phone must be rejected", discoveredAmbiguous.phones.isEmpty())
        assertTrue("Ambiguous handle must be rejected", discoveredAmbiguous.handles.isEmpty())
    }

    @Test
    fun rejection_softExistenceAndCandidateStatesRejected() {
        val softResult = verifiedResult(
            username = "soft_user",
            url = "https://github.test/soft_user",
            providerVerificationState = ProviderVerificationState.SoftNotFound,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "soft@example.test",
                    sourceUrl = "https://github.test/soft_user",
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )
        val authResult = verifiedResult(
            username = "auth_user",
            url = "https://github.test/auth_user",
            providerVerificationState = ProviderVerificationState.AuthenticationRequired
        )
        val input = IdentityInput(fullName = "Jane Doe")

        val discoveredSoft = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(softResult))
        assertTrue("SoftNotFound provider state must be rejected", discoveredSoft.isEmpty)

        val discoveredAuth = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(authResult))
        assertTrue("AuthenticationRequired provider state must be rejected", discoveredAuth.isEmpty)
    }

    @Test
    fun normalization_standardizesEmailPhoneAndHandle() {
        val profileUrl = "https://github.test/raw_user"
        val result = verifiedResult(
            username = "  @Jane_Doe  ",
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "  Jane.Doe@EXAMPLE.TEST  ",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "+1 (555) 010-4444",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )
        val input = IdentityInput(fullName = "Jane Doe")

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result))

        assertEquals(listOf("jane.doe@example.test"), discovered.emails)
        assertEquals(listOf("15550104444"), discovered.phones)
        assertEquals(listOf("Jane_Doe"), discovered.handles)
    }

    @Test
    fun deterministicDeduplication_ignoresAlreadySuppliedAndDuplicateTerms() {
        val profileUrl = "https://github.test/janedoe"
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            usernames = listOf("jane_alias"),
            emails = listOf("jane@example.test"),
            phones = listOf("+1 555 010 5555")
        )
        val result1 = verifiedResult(
            username = "JANEDOE", // Duplicate of primaryUsername
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "JANE@EXAMPLE.TEST", // Duplicate of supplied email
                    sourceUrl = profileUrl,
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "15550105555", // Duplicate of supplied phone
                    sourceUrl = profileUrl,
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )
        val result2 = verifiedResult(
            username = "jane_alias", // Duplicate of input.usernames
            url = "https://gitlab.test/jane_alias",
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "new@example.test",
                    sourceUrl = "https://gitlab.test/jane_alias",
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Email,
                    value = "NEW@EXAMPLE.TEST", // Duplicate of earlier finding in same batch
                    sourceUrl = "https://gitlab.test/jane_alias",
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result1, result2))

        assertEquals(listOf("new@example.test"), discovered.emails)
        assertTrue("Duplicate phones must not be accepted", discovered.phones.isEmpty())
        assertTrue("Duplicate handles must not be accepted", discovered.handles.isEmpty())
    }

    @Test
    fun originalInputPreservation_neverMutatesOrOverwritesPrimaryUsername() {
        val originalInput = IdentityInput(
            fullName = "Jane Doe",
            aliases = listOf("JD"),
            emails = listOf("ORIG@EXAMPLE.TEST", "  ", "orig@example.test"),
            phones = listOf("+1 (555) 010-0000", "+1 (555) 010-0000"),
            locations = listOf("Berlin"),
            organizations = listOf("Acme Labs"),
            usernames = listOf("Orig_User", "Orig_User"),
            primaryUsername = "original_primary",
            profileUrls = listOf("https://orig.test/jane"),
            selfieUri = "content://media/selfie"
        )
        val profileUrl = "https://github.test/discovered_handle"
        val result = verifiedResult(
            username = "discovered_handle",
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "discovered@example.test",
                    sourceUrl = profileUrl,
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "+1 555 010 7777",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )

        val expanded = PublicSearchDiscoveryService.expandIdentityInput(originalInput, listOf(result))

        // Assert original input remains intact
        assertEquals(listOf("ORIG@EXAMPLE.TEST", "  ", "orig@example.test"), originalInput.emails)
        assertEquals(listOf("+1 (555) 010-0000", "+1 (555) 010-0000"), originalInput.phones)
        assertEquals(listOf("Orig_User", "Orig_User"), originalInput.usernames)

        // Assert expanded input preserved all fields and did not overwrite primaryUsername
        assertEquals("Jane Doe", expanded.fullName)
        assertEquals("original_primary", expanded.primaryUsername)
        assertEquals(listOf("JD"), expanded.aliases)
        assertEquals(listOf("Berlin"), expanded.locations)
        assertEquals(listOf("Acme Labs"), expanded.organizations)
        assertEquals(listOf("https://orig.test/jane"), expanded.profileUrls)
        assertEquals("content://media/selfie", expanded.selfieUri)

        // Newly accepted high-entropy terms prepended while preserving exact original list items
        assertEquals(
            listOf("discovered@example.test", "ORIG@EXAMPLE.TEST", "  ", "orig@example.test"),
            expanded.emails
        )
        assertEquals(
            listOf("15550107777", "+1 (555) 010-0000", "+1 (555) 010-0000"),
            expanded.phones
        )
        assertEquals(
            listOf("discovered_handle", "Orig_User", "Orig_User"),
            expanded.usernames
        )
    }

    @Test
    fun discoveredTermQueryOrdering_placesNewHighEntropyTermsAheadOfOriginalAndBroadTerms() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "original_handle",
            emails = listOf("orig@example.test"),
            phones = listOf("+1 555 010 0000")
        )
        val profileUrl = "https://github.test/new_handle"
        val result = verifiedResult(
            username = "new_handle",
            url = profileUrl,
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "new@example.test",
                    sourceUrl = profileUrl,
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                ),
                Finding(
                    type = FindingType.Phone,
                    value = "+1 555 010 8888",
                    sourceUrl = profileUrl,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            )
        )

        val queries = PublicSearchDiscoveryService.buildSearchQueries(
            input = input,
            deepResearch = false,
            verifiedResults = listOf(result)
        )

        val newEmailIndex = queries.indexOf("\"new@example.test\"")
        val origEmailIndex = queries.indexOf("\"orig@example.test\"")
        val newPhoneIndex = queries.indexOf("\"15550108888\"")
        val origPhoneIndex = queries.indexOf("\"15550100000\"")
        val newHandleIndex = queries.indexOf("\"new_handle\"")
        val origHandleIndex = queries.indexOf("\"original_handle\"")
        val broadNameIndex = queries.indexOfFirst { it == "\"Jane Doe\"" }

        assertTrue("New email query must be present", newEmailIndex >= 0)
        assertTrue("Orig email query must be present", origEmailIndex >= 0)
        assertTrue("New email must precede original email", newEmailIndex < origEmailIndex)

        assertTrue("New phone query must be present", newPhoneIndex >= 0)
        assertTrue("Orig phone query must be present", origPhoneIndex >= 0)
        assertTrue("New phone must precede original phone", newPhoneIndex < origPhoneIndex)

        assertTrue("New handle query must be present", newHandleIndex >= 0)
        assertTrue("Orig handle query must be present", origHandleIndex >= 0)
        assertTrue("New handle must precede original handle", newHandleIndex < origHandleIndex)

        assertTrue("Broad name query must be present", broadNameIndex >= 0)
        assertTrue("New email must precede broad name", newEmailIndex < broadNameIndex)
        assertTrue("New phone must precede broad name", newPhoneIndex < broadNameIndex)
        assertTrue("New handle must precede broad name", newHandleIndex < broadNameIndex)
    }

    @Test
    fun boundedExpansion_respectsMaxPerKindAndTotalCaps() {
        val input = IdentityInput(fullName = "Jane Doe")
        val results = (1..10).map { i ->
            val url = "https://github.test/user$i"
            verifiedResult(
                username = "user$i",
                url = url,
                findings = listOf(
                    Finding(
                        type = FindingType.Email,
                        value = "user$i@example.test",
                        sourceUrl = url,
                        confidence = 0.90f,
                        risk = RiskLevel.High,
                        evidenceSnippet = "contact",
                        remediation = "remediate"
                    ),
                    Finding(
                        type = FindingType.Phone,
                        value = "+1 555 010 000$i",
                        sourceUrl = url,
                        confidence = 0.90f,
                        risk = RiskLevel.High,
                        evidenceSnippet = "contact",
                        remediation = "remediate"
                    )
                )
            )
        }

        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, results)

        assertTrue(
            "Emails must not exceed MAX_EXPANDED_EMAILS",
            discovered.emails.size <= PublicSearchDiscoveryService.MAX_EXPANDED_EMAILS
        )
        assertTrue(
            "Phones must not exceed MAX_EXPANDED_PHONES",
            discovered.phones.size <= PublicSearchDiscoveryService.MAX_EXPANDED_PHONES
        )
        assertTrue(
            "Handles must not exceed MAX_EXPANDED_HANDLES",
            discovered.handles.size <= PublicSearchDiscoveryService.MAX_EXPANDED_HANDLES
        )
        assertTrue(
            "Total discovered terms must not exceed MAX_TOTAL_EXPANDED_TERMS",
            discovered.totalCount <= PublicSearchDiscoveryService.MAX_TOTAL_EXPANDED_TERMS
        )
        assertEquals(4, discovered.emails.size)
        assertEquals(4, discovered.phones.size)
        assertEquals(8, discovered.handles.size)
        assertEquals(16, discovered.totalCount)
    }

    @Test
    fun confidenceBounds_rejectsNonFiniteAndOutOfRangeConfidences() {
        val profileUrl = "https://github.test/janedoe"
        val findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "nan@example.test",
                sourceUrl = profileUrl,
                confidence = Float.NaN,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Email,
                value = "inf@example.test",
                sourceUrl = profileUrl,
                confidence = Float.POSITIVE_INFINITY,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Email,
                value = "neginf@example.test",
                sourceUrl = profileUrl,
                confidence = Float.NEGATIVE_INFINITY,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Email,
                value = "toolarge@example.test",
                sourceUrl = profileUrl,
                confidence = 1.5f,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+1 555 010 1111",
                sourceUrl = profileUrl,
                confidence = Float.NaN,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+1 555 010 2222",
                sourceUrl = profileUrl,
                confidence = Float.POSITIVE_INFINITY,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            ),
            Finding(
                type = FindingType.Phone,
                value = "+1 555 010 3333",
                sourceUrl = profileUrl,
                confidence = 1.2f,
                risk = RiskLevel.High,
                evidenceSnippet = "contact",
                remediation = "remediate"
            )
        )
        val result = verifiedResult(
            username = "valid_handle",
            url = profileUrl,
            findings = findings
        )
        val input = IdentityInput(fullName = "Jane Doe")
        val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(result))

        assertTrue("Non-finite and out-of-range emails must be rejected", discovered.emails.isEmpty())
        assertTrue("Non-finite and out-of-range phones must be rejected", discovered.phones.isEmpty())
        assertEquals(listOf("valid_handle"), discovered.handles)
    }

    @Test
    fun ambiguityMarkersInMetadata_failClosedForVerifiedLookingResults() {
        val profileUrl = "https://github.test/janedoe"
        val finding = Finding(
            type = FindingType.Email,
            value = "test@example.test",
            sourceUrl = profileUrl,
            confidence = 0.90f,
            risk = RiskLevel.High,
            evidenceSnippet = "contact",
            remediation = "remediate"
        )
        val ambiguousCases = listOf(
            verifiedResult(username = "u1", url = profileUrl, verificationStatus = "Exists but not attributed to this identity — possible account", findings = listOf(finding)),
            verifiedResult(username = "u2", url = profileUrl, verificationStatus = "Candidate lead only", findings = listOf(finding)),
            verifiedResult(username = "u3", url = profileUrl, verificationStatus = "Review-only match", findings = listOf(finding)),
            verifiedResult(username = "u4", url = profileUrl, provenance = "possible-account", findings = listOf(finding)),
            verifiedResult(username = "u5", url = profileUrl, provenance = "not-attributed", findings = listOf(finding)),
            verifiedResult(username = "u6", url = profileUrl, provenance = "unknown-origin", findings = listOf(finding)),
            verifiedResult(username = "u7", url = profileUrl, provenance = "review-only", findings = listOf(finding)),
            verifiedResult(username = "u8", url = profileUrl, provenance = "candidate", findings = listOf(finding))
        )
        val input = IdentityInput(fullName = "Jane Doe")
        ambiguousCases.forEach { ambiguousResult ->
            val discovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(ambiguousResult))
            assertTrue(
                "Result with status '${ambiguousResult.verificationStatus}' or provenance '${ambiguousResult.provenance}' must fail closed",
                discovered.isEmpty
            )
        }

        // Genuinely direct verified statuses and null legacy metadata must be accepted
        val directHttp = verifiedResult(
            username = "direct_http_user",
            url = profileUrl,
            verificationStatus = "✓ Verified (HTTP 200, direct page access)",
            provenance = "verified-profile",
            findings = listOf(finding)
        )
        val directBrowser = verifiedResult(
            username = "direct_browser_user",
            url = profileUrl,
            verificationStatus = "✓ Verified in-browser",
            provenance = "verified-profile",
            findings = listOf(finding)
        )
        val nullLegacy = verifiedResult(
            username = "legacy_user",
            url = profileUrl,
            verificationStatus = null,
            provenance = null,
            findings = listOf(finding)
        )

        val discoveredDirect = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(directHttp))
        assertFalse(discoveredDirect.isEmpty)
        assertEquals(listOf("direct_http_user"), discoveredDirect.handles)

        val discoveredBrowser = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(directBrowser))
        assertFalse(discoveredBrowser.isEmpty)
        assertEquals(listOf("direct_browser_user"), discoveredBrowser.handles)

        val discoveredLegacy = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(nullLegacy))
        assertFalse(discoveredLegacy.isEmpty)
        assertEquals(listOf("legacy_user"), discoveredLegacy.handles)
    }

    @Test
    fun roundRobinQueryOrdering_defaultQueryCapCannotStarveDiscoveredHandles() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "original_handle",
            emails = listOf("orig@example.test"),
            phones = listOf("+1 555 010 0000")
        )
        // 8 handles, 4 emails, 4 phones discovered across verified results
        val results = (1..8).map { i ->
            val url = "https://github.test/user$i"
            val findings = mutableListOf<Finding>()
            if (i <= 4) {
                findings += Finding(
                    type = FindingType.Email,
                    value = "user$i@example.test",
                    sourceUrl = url,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
                findings += Finding(
                    type = FindingType.Phone,
                    value = "+1 555 010 000$i",
                    sourceUrl = url,
                    confidence = 0.90f,
                    risk = RiskLevel.High,
                    evidenceSnippet = "contact",
                    remediation = "remediate"
                )
            }
            verifiedResult(
                username = "user$i",
                url = url,
                findings = findings
            )
        }

        val queries = PublicSearchDiscoveryService.buildSearchQueries(input, deepResearch = false, verifiedResults = results)
        val cappedQueries = queries.take(24)

        // All 8 discovered handles must be present in the capped 24 queries
        for (i in 1..8) {
            assertTrue("Discovered handle user$i must not be starved by 24 query cap", cappedQueries.contains("\"user$i\""))
        }

        // All 4 discovered emails must be present in the capped 24 queries
        for (i in 1..4) {
            assertTrue("Discovered email user$i@example.test must be present in capped queries", cappedQueries.contains("\"user$i@example.test\""))
        }

        // All 4 discovered phones must be present in the capped 24 queries
        for (i in 1..4) {
            assertTrue("Discovered phone 1555010000$i must be present in capped queries", cappedQueries.contains("\"1555010000$i\""))
        }

        // Phase 1 guarantees all 16 discovered exact queries appear before any original-term queries
        val first16 = cappedQueries.take(16)
        for (i in 1..8) {
            assertTrue("First 16 queries must contain discovered handle user$i", first16.contains("\"user$i\""))
        }
        for (i in 1..4) {
            assertTrue("First 16 queries must contain discovered email user$i@example.test", first16.contains("\"user$i@example.test\""))
            assertTrue("First 16 queries must contain discovered phone 1555010000$i", first16.contains("\"1555010000$i\""))
        }
    }

    @Test
    fun verifiedProfileAssembly_promotesDirectSameSourceFindingsToExpansion() {
        val profileUrl = "https://github.test/janedoe"
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe"
        )
        // PiiExtractor generates ~0.72 for non-supplied associated email and ~0.68 for phone
        val piiExtractor = io.dossier.app.domain.pii.PiiExtractor()
        val rawPageText = "Jane Doe profile. Contact me at jane.discovered@example.test or call +1 (555) 010-8899 for inquiries."
        val extractedFindings = piiExtractor.extract(rawPageText, profileUrl, input)

        val emailFinding = extractedFindings.first { it.type == FindingType.Email }
        val phoneFinding = extractedFindings.first { it.type == FindingType.Phone }

        // Verify PiiExtractor assigned ~0.72 and ~0.68 before promotion
        assertEquals(0.72f, emailFinding.confidence, 0.01f)
        assertEquals(0.68f, phoneFinding.confidence, 0.01f)

        // Directly passing unpromoted findings to search expansion would reject them because 0.72 < 0.80 and 0.68 < 0.80
        val unpromotedResult = verifiedResult(
            username = "janedoe",
            url = profileUrl,
            findings = listOf(emailFinding, phoneFinding)
        )
        val unpromotedDiscovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(unpromotedResult))
        assertTrue("Unpromoted findings must be rejected under 0.80 gate", unpromotedDiscovered.emails.isEmpty())
        assertTrue("Unpromoted findings must be rejected under 0.80 gate", unpromotedDiscovered.phones.isEmpty())

        // Simulated verified profile assembly in ProfileScanner (where direct same-source findings are promoted to >= 0.80)
        val promotedFindings = listOf(
            emailFinding.copy(confidence = maxOf(emailFinding.confidence, 0.85f)),
            phoneFinding.copy(confidence = maxOf(phoneFinding.confidence, 0.85f))
        )
        val verifiedAssembledResult = verifiedResult(
            username = "janedoe",
            url = profileUrl,
            findings = promotedFindings
        )

        val promotedDiscovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(verifiedAssembledResult))
        assertEquals(listOf("jane.discovered@example.test"), promotedDiscovered.emails)
        assertEquals(listOf("15550108899"), promotedDiscovered.phones)

        // However, if the profile was unverified or candidate, promotion must NOT occur and findings remain rejected
        val unverifiedWithLowConf = verifiedResult(
            username = "candidate_user",
            url = profileUrl,
            verified = false,
            providerVerificationState = ProviderVerificationState.SoftNotFound,
            findings = listOf(emailFinding, phoneFinding)
        )
        val candidateDiscovered = PublicSearchDiscoveryService.extractDiscoveredSearchTerms(input, listOf(unverifiedWithLowConf))
        assertTrue(candidateDiscovered.isEmpty)
    }
}
