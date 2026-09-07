package io.dossier.app.data.web

import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.discovery.TypedSeedSafety
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.evidence.toFinding
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.data.web.PublicSearchDiscoveryService.PublicSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicInteger

class TypedSeedPublicFetchExecutorTest {

    private val scanId = ScanId("typed-seed-test")
    private val input = IdentityInput(
        fullName = "Jane Example",
        emails = listOf("jane@example.test"),
        phones = listOf("15550100100")
    )

    @Test
    fun urlExecutionExtractsExactPiiAndLinks() = runBlocking {
        val url = "https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Url, url)
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                """
                    <html><head><title>Jane Example</title></head>
                    <body>
                    <p>Jane Example public contact jane.public@example.test and phone +1 555 010 0100.</p>
                    <a href="https://portfolio.example.test/about">Portfolio</a>
                    </body></html>
                """.trimIndent()
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Verified, report.executions.single().state)
        assertTrue(report.evidence.any { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Email && it.value == "jane.public@example.test" })
        assertTrue(report.evidence.any { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Phone && it.value == "+1 555 010 0100" })
        assertTrue(report.evidence.any { it.value == "https://portfolio.example.test/about" })
        assertTrue(report.evidence.any { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Domain && it.value == "portfolio.example.test" })
        assertTrue(report.evidence.all { it.sourceClassification == ExposureSourceClassification.PUBLIC_WEB })
        assertTrue(report.collection.relationships.any { it.relation == "links_to" })
    }

    @Test
    fun typedPublicFetchPreservesExactSelfSuppliedAttribution() = runBlocking {
        val url = "https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Url, url)
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                "<html><body>Jane Example public contact jane@example.test.</body></html>"
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val email = report.evidence.single {
            it.kind == io.dossier.app.domain.evidence.EvidenceKind.Email &&
                it.value == "jane@example.test"
        }

        assertEquals(EvidenceState.Verified, email.state)
        assertEquals(FindingAttribution.ExactSelfSupplied, email.attribution)
        assertEquals(FindingAttribution.ExactSelfSupplied, email.toFinding().attribution)
        assertEquals(
            FindingAttribution.ExactSelfSupplied,
            report.collection.toExposureLedger().facts.single {
                it.exactValue == "jane@example.test"
            }.attribution
        )
    }

    @Test
    fun sourceClassificationIsRetainedForDocumentsAndDownstreamRecords() = runBlocking {
        val documentSeed = userSeed(TypedSeedKind.Document, "https://docs.example.test/resume")
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                """
                    <html><body>
                    Public contact jane.public@example.test.
                    <a href="https://portfolio.example.test/about">Portfolio</a>
                    <a href="https://docs.example.test/cv.pdf">CV</a>
                    </body></html>
                """.trimIndent()
            )
        }

        val report = executor.executeDetailed(listOf(documentSeed), input, scanId)

        assertTrue(report.evidence.isNotEmpty())
        assertTrue(report.evidence.all {
            it.sourceClassification == ExposureSourceClassification.PUBLIC_DOCUMENT
        })
        assertTrue(
            report.evidence.any {
                it.kind == io.dossier.app.domain.evidence.EvidenceKind.Email &&
                    it.value == "jane.public@example.test"
            }
        )
        val ledger = report.collection.toExposureLedger()
        assertTrue(ledger.facts.isNotEmpty())
        assertTrue(ledger.facts.all {
            it.sourceClassification == ExposureSourceClassification.PUBLIC_DOCUMENT
        })
    }

    @Test
    fun unsafeProviderFinalUrlFailsClosedInsteadOfUsingRequestedUrl() = runBlocking {
        val requested = "https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Url, requested)
        val executor = executor { _, _, _, _ ->
            ProviderExecutionResult(
                decision = ProviderResponseDecision(ProviderVerificationState.Present, "fixture present"),
                statusCode = 200,
                requestedUrl = requested,
                finalUrl = "http://127.0.0.1/private",
                bodyText = "<html><body>jane.public@example.test</body></html>",
                latencyMs = 1,
                attemptCount = 1
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val execution = report.executions.single()

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, execution.state)
        assertTrue(execution.reason.orEmpty().contains("unsafe final URL", ignoreCase = true))
        assertTrue(report.evidence.none { it.state == EvidenceState.Verified })
    }

    @Test
    fun binaryContentTypeIsUnavailableEvenWhenUrlLooksLikeHtml() = runBlocking {
        val requested = "https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Url, requested)
        val executor = executor { _, url, _, _ ->
            present(
                url,
                "<html><body>not actually html</body></html>",
                contentType = "application/pdf; charset=binary"
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.single().state)
        assertTrue(report.executions.single().reason.orEmpty().contains("format", ignoreCase = true))
        assertTrue(report.evidence.none { it.state == EvidenceState.Verified })
    }

    @Test
    fun imageAndUnknownBinaryResponsesRemainUnavailable() = runBlocking {
        val image = userSeed(TypedSeedKind.Url, "https://profile.example.test/avatar")
        val binary = userSeed(TypedSeedKind.Url, "https://profile.example.test/download")
        val executor = executor { _, requested, _, _ ->
            if (requested.endsWith("avatar")) {
                present(requested, "\u0089PNG\r\n\u001a\nimage bytes", contentType = "image/png")
            } else {
                present(requested, "binary\u0000\u0001\u0002payload")
            }
        }

        val report = executor.executeDetailed(listOf(image, binary), input, scanId)

        assertEquals(2, report.executions.size)
        assertTrue(report.executions.all { it.state == TypedSeedPublicFetchExecutor.ExecutionState.Unavailable })
        assertTrue(report.evidence.all { it.state == EvidenceState.Unavailable })
    }

    @Test
    fun unassociatedPiiIsObservedButCannotBecomeARecursivePivot() = runBlocking {
        val email = "unrelated.public@example.test"
        val phone = "+1 555 010 0199"
        val seed = userSeed(TypedSeedKind.Url, "https://profile.example.test/page")
        val executor = executor { _, requested, _, _ ->
            present(requested, "<html><body>Contact $email or $phone</body></html>")
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val emailEvidence = report.evidence.single { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Email && it.value == email }
        val phoneEvidence = report.evidence.single { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Phone && it.value == phone }
        val admitted = TypedSeedEvidenceAdapter.fromCollection(report.collection, input).admittedSeeds

        assertEquals(EvidenceState.Observed, emailEvidence.state)
        assertEquals(EvidenceState.Observed, phoneEvidence.state)
        assertTrue(admitted.none { it.exactValue == email || it.exactValue == phone })
    }

    @Test
    fun unrelatedExtractedLinksAreObservedButRemainFetchOnlyPivots() = runBlocking {
        val seed = userSeed(TypedSeedKind.Url, "https://profile.example.test/page")
        val unrelated = "https://other.example.org/private"
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                "<html><body><a href=\"$unrelated\">external</a></body></html>"
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val link = report.evidence.single { it.value == unrelated }
        val admitted = TypedSeedEvidenceAdapter.fromCollection(report.collection, input).admittedSeeds

        assertEquals(EvidenceState.Observed, link.state)
        val pivot = admitted.single { it.normalizedValue == unrelated }
        assertTrue(TypedSeedSafety.isSafePublicFetchSeed(pivot))
        assertFalse(TypedSeedSafety.isSafePublicSearchSeed(pivot))
    }

    @Test
    fun publicSuffixTenantsAreNotMistakenForSameSiteLinks() = runBlocking {
        val seed = userSeed(TypedSeedKind.Url, "https://a.example.co.uk/page")
        val coUkTenant = "https://b.other.co.uk/contact"
        val githubTenant = "https://foo.github.io/about"
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                "<html><body><a href=\"$coUkTenant\">one</a><a href=\"$githubTenant\">two</a></body></html>"
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(EvidenceState.Observed, report.evidence.single { it.value == coUkTenant }.state)
        assertEquals(EvidenceState.Observed, report.evidence.single { it.value == githubTenant }.state)
    }

    @Test
    fun snippetMarkersCannotUpgradeUnconfirmedFinding() = runBlocking {
        val seed = userSeed(TypedSeedKind.Url, "https://profile.example.test/page")
        val executor = executor { _, requested, _, _ ->
            present(
                requested,
                "<html><body>noise@example.test [exact self-supplied identifier]</body></html>"
            )
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val noise = report.evidence.single { it.value == "noise@example.test" }

        assertEquals(EvidenceState.Observed, noise.state)
    }

    @Test
    fun officeZipMagicIsUnavailableWhenContentTypeIsMissing() = runBlocking {
        val requested = "https://docs.example.test/resume"
        val seed = userSeed(TypedSeedKind.Document, requested)
        val executor = executor { _, url, _, _ ->
            present(url, "PK\u0003\u0004\u0014\u0000binary office payload")
        }

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.single().state)
        assertTrue(report.executions.single().reason.orEmpty().contains("format", ignoreCase = true))
    }

    @Test
    fun domainSeedsAreDeduplicatedAndBoundedBeforeFetch() = runBlocking {
        val calls = AtomicInteger(0)
        val duplicate = userSeed(TypedSeedKind.Domain, "example.test")
        val seeds = listOf(duplicate, duplicate.copy(exactValue = "EXAMPLE.TEST", value = "example.test")) +
            (1..20).map { index -> userSeed(TypedSeedKind.Domain, "domain$index.example.test") }
        val executor = executor { _, requested, _, _ ->
            calls.incrementAndGet()
            present(requested, "<html><body>Domain landing page</body></html>")
        }

        val report = executor.executeDetailed(seeds, input, scanId)

        assertTrue(calls.get() <= TypedSeedPublicFetchExecutor.MAX_SEEDS)
        assertEquals(calls.get(), report.executions.count { it.fetchAttempted })
        assertTrue(
            report.executions
                .filter { it.fetchAttempted }
                .map { it.seed.normalizedValue }
                .distinct()
                .size <= TypedSeedPublicFetchExecutor.MAX_SEEDS
        )
        assertFalse(
            report.executions
                .filter { it.reason.orEmpty().contains("bounded execution budget", ignoreCase = true) }
                .any { it.fetchAttempted }
        )
        assertEquals(
            seeds.distinctBy { "${it.kind}:${it.normalizedValue}" }.size,
            report.executions.size
        )
        assertTrue(report.executions.any {
            it.reason.orEmpty().contains("bounded execution budget", ignoreCase = true)
        })
    }

    @Test
    fun archiveEvidenceIsHistoricalAndDocumentUnsupportedStateIsExplicit() = runBlocking {
        val archiveSeed = userSeed(
            kind = TypedSeedKind.Archive,
            value = "https://web.archive.org/web/20240101000000/https://profile.example.test/jane"
        )
        val documentSeed = userSeed(TypedSeedKind.Document, "https://docs.example.test/resume.pdf")
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                if (requested.contains("web.archive.org")) {
                    present(requested, "<html><body>Jane Example jane.archive@example.test</body></html>")
                } else {
                    present(requested, "%PDF-1.7 not parsed")
                }
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                TypedSeedPublicFetchExecutor.ArchiveSeedFetch(
                    provider = "fixture-archive",
                    originalUrl = "https://profile.example.test/jane",
                    snapshotUrl = archiveSeed.exactValue,
                    timestamp = "20240101000000",
                    title = "Jane Example (historical)",
                    text = "Jane Example jane.archive@example.test"
                )
            }
        )

        val report = executor.executeDetailed(listOf(archiveSeed, documentSeed), input, scanId)
        val archiveEvidence = report.evidence.filter { it.sourceUrl == archiveSeed.exactValue }
        val unavailable = report.evidence.single { it.kind == io.dossier.app.domain.evidence.EvidenceKind.Document }

        assertTrue(archiveEvidence.isNotEmpty())
        assertTrue(archiveEvidence.all { it.historical })
        assertTrue(archiveEvidence.all { it.reliability == EvidenceReliability.ArchiveSnapshot })
        assertTrue(archiveEvidence.any { it.value == "jane.archive@example.test" && it.state == EvidenceState.Observed })
        assertTrue(archiveEvidence.none { it.value == "jane.archive@example.test" && it.state == EvidenceState.Verified })
        assertEquals(EvidenceState.Unavailable, unavailable.state)
        assertTrue(unavailable.snippet.orEmpty().contains("not supported", ignoreCase = true))
    }

    @Test
    fun directWaybackSnapshotBypassesOriginalUrlResolverAndRetainsArchiveProvenance() = runBlocking {
        val original = "https://profile.example.test/jane"
        val snapshot = "https://web.archive.org/web/20240102030405id_/$original"
        val seed = userSeed(TypedSeedKind.Archive, snapshot)
        val resolverCalls = AtomicInteger(0)
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                assertEquals(snapshot, requested)
                present(requested, "<html><body>Jane Example jane.archive@example.test</body></html>")
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                resolverCalls.incrementAndGet()
                throw IllegalStateException("a snapshot must not be looked up as an original URL")
            }
        )

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val snapshotEvidence = report.evidence.first { it.value == snapshot }

        assertEquals(0, resolverCalls.get())
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Verified, report.executions.single().state)
        assertEquals(EvidenceReliability.ArchiveSnapshot, snapshotEvidence.reliability)
        assertEquals(ExposureSourceClassification.ARCHIVE, snapshotEvidence.sourceClassification)
        assertTrue(original in snapshotEvidence.sourceUrls)
        assertTrue(original in snapshotEvidence.discoveryPath)
        assertTrue(snapshot in snapshotEvidence.discoveryPath)
        assertTrue(report.collection.relationships.any {
            it.fromValue == original && it.toValue == snapshot && it.relation == "ARCHIVED_AS" &&
                snapshotEvidence.id in it.evidenceIds
        })
    }

    @Test
    fun archiveExecutionRejectsNonArchiveResolverSnapshot() = runBlocking {
        val seed = userSeed(TypedSeedKind.Archive, "https://profile.example.test/jane")
        val executor = TypedSeedPublicFetchExecutor(
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                TypedSeedPublicFetchExecutor.ArchiveSeedFetch(
                    provider = "fixture-archive",
                    originalUrl = seed.exactValue,
                    snapshotUrl = "https://evil.example.test/not-an-archive",
                    text = "Jane Example"
                )
            }
        )

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(
            TypedSeedPublicFetchExecutor.ExecutionState.Unavailable,
            report.executions.single().state
        )
        assertTrue(report.executions.single().reason.orEmpty().contains("non-archive", ignoreCase = true))
        assertTrue(report.evidence.all { it.state != EvidenceState.Verified })
    }

    @Test
    fun directArchiveRedirectMustRemainAnArchiveSnapshot() = runBlocking {
        val snapshot = "https://web.archive.org/web/20240102030405id_/https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Archive, snapshot)
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                present(
                    requested,
                    "<html><body>Jane Example</body></html>",
                    contentType = "text/html"
                ).copy(finalUrl = "https://profile.example.test/jane")
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                throw IllegalStateException("direct snapshot should not resolve")
            }
        )

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(
            TypedSeedPublicFetchExecutor.ExecutionState.Unavailable,
            report.executions.single().state
        )
        assertTrue(report.executions.single().reason.orEmpty().contains("non-archive", ignoreCase = true))
        assertTrue(report.evidence.all { it.state != EvidenceState.Verified })
    }

    @Test
    fun partialArchiveCapturePrecisionDoesNotFabricateObservationTimestamp() = runBlocking {
        val snapshot = "https://web.archive.org/web/202401/https://profile.example.test/jane"
        val seed = userSeed(TypedSeedKind.Archive, snapshot)
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                present(requested, "<html><body>Jane Example</body></html>")
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                throw IllegalStateException("partial snapshots are fetched directly")
            }
        )

        val report = executor.executeDetailed(listOf(seed), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Verified, report.executions.single().state)
        assertTrue(report.evidence.isNotEmpty())
        assertTrue(report.evidence.all { it.observedAtEpochMillis == null })
        assertNull(TypedSeedPublicFetchExecutor.classifyArchiveSnapshot(snapshot)?.let { it.timestamp?.takeIf { ts -> ts.length == 14 } })
    }

    @Test
    fun unsafeOversizedAndFailedSeedsRemainUnavailableWithoutStoppingSiblings() = runBlocking {
        val calls = AtomicInteger(0)
        val unsafe = userSeed(TypedSeedKind.Url, "http://127.0.0.1/private")
        val oversized = userSeed(TypedSeedKind.Url, "https://large.example.test/page")
        val failed = userSeed(TypedSeedKind.Url, "https://failed.example.test/page")
        val healthy = userSeed(TypedSeedKind.Url, "https://healthy.example.test/page")
        val executor = executor { _, requested, _, _ ->
            calls.incrementAndGet()
            when {
                requested.contains("large") -> present(requested, "x".repeat(TypedSeedPublicFetchExecutor.RESPONSE_TEXT_CHARS + 1))
                requested.contains("failed") -> error("fixture failure")
                else -> present(requested, "<html><body>Healthy page jane.public@example.test</body></html>")
            }
        }

        val report = executor.executeDetailed(listOf(unsafe, oversized, failed, healthy), input, scanId)

        assertEquals(3, calls.get())
        assertFalse(report.executions.first { it.seed == unsafe }.fetchAttempted)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.first { it.seed == oversized }.state)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.first { it.seed == failed }.state)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Verified, report.executions.first { it.seed == healthy }.state)
        assertTrue(report.evidence.any { it.sourceUrl == "https://healthy.example.test/page" && it.state == EvidenceState.Verified })
        val failedEvidence = report.evidence.single { it.sourceUrl == "https://failed.example.test/page" }
        assertEquals(EvidenceReliability.DirectPersonalWebsite, failedEvidence.reliability)
        assertEquals(ExposureSourceClassification.PUBLIC_WEB, failedEvidence.sourceClassification)
    }

    @Test
    fun childCancellationIsIsolatedWhileParentRemainsActiveAndPathIsBounded() = runBlocking {
        val cancelled = userSeed(TypedSeedKind.Url, "https://cancelled.example.test/page").copy(
            discoveryPath = (1..TypedSeed.MAX_DISCOVERY_PATH_STEPS).map { "hop-$it" }
        )
        val healthy = userSeed(TypedSeedKind.Url, "https://healthy.example.test/page")
        val executor = executor { _, requested, _, _ ->
            if (requested.contains("cancelled")) throw CancellationException("fixture cancellation")
            present(requested, "<html><body>Healthy page</body></html>")
        }

        val report = executor.executeDetailed(listOf(cancelled, healthy), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.first { it.seed == cancelled }.state)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Verified, report.executions.first { it.seed == healthy }.state)
        assertTrue(report.evidence.all { it.discoveryPath.size <= Evidence.MAX_DISCOVERY_PATH_STEPS })
    }

    @Test
    fun emailAndPhoneSeedsExecuteViaSearchSeamAndReturnTerminalCompletedState() = runBlocking {
        val email = evidenceSeed(TypedSeedKind.Email, "target@example.test")
        val executor = TypedSeedPublicFetchExecutor(
            searcher = { seed, _, _ ->
                listOf(
                    PublicSearchResult(
                        title = "Target Profile",
                        snippet = "Email target@example.test",
                        url = "https://social.example.test/target",
                        query = seed.exactValue,
                        source = "Fixture Search",
                        score = 0.9f
                    )
                )
            }
        )

        val report = executor.executeDetailed(listOf(email), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, report.executions.single().state)
        assertTrue(report.evidence.any { it.kind == io.dossier.app.domain.evidence.EvidenceKind.PublicSearchEvidence && it.value == "https://social.example.test/target" })
        assertTrue(report.evidence.all { it.state == EvidenceState.Candidate })
        assertTrue(report.evidence.all { it.attribution == FindingAttribution.Unconfirmed })
    }

    @Test
    fun candidateOrImportedSearchSeedsAreRejectedBeforeFetch() = runBlocking {
        val candidateEmail = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "candidate@example.test",
            exactValue = "candidate@example.test",
            normalizedValue = "candidate@example.test",
            origin = TypedSeedOrigin.Candidate,
            evidenceState = EvidenceState.Candidate,
            sourceClassification = ExposureSourceClassification.UNKNOWN_ORIGIN
        )
        var calls = 0
        val executor = TypedSeedPublicFetchExecutor(
            searcher = { _, _, _ ->
                calls++
                emptyList()
            }
        )

        val report = executor.executeDetailed(listOf(candidateEmail), input, scanId)

        assertEquals(0, calls)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Candidate, report.executions.single().state)
    }

    @Test
    fun searchSeamFailureYieldsUnavailableWithoutCancellingSiblings() = runBlocking {
        val failedEmail = evidenceSeed(TypedSeedKind.Email, "failed@example.test")
        val healthyPhone = evidenceSeed(TypedSeedKind.Phone, "15550100200")
        val executor = TypedSeedPublicFetchExecutor(
            searcher = { seed, _, _ ->
                if (seed.kind == TypedSeedKind.Email) {
                    throw IllegalStateException("Search fixture failure")
                }
                emptyList()
            }
        )

        val report = executor.executeDetailed(listOf(failedEmail, healthyPhone), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.first { it.seed == failedEmail }.state)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, report.executions.first { it.seed == healthyPhone }.state)
    }

    @Test
    fun emptySearchResultYieldsCompletedTerminalState() = runBlocking {
        val phone = evidenceSeed(TypedSeedKind.Phone, "15550100300")
        val executor = TypedSeedPublicFetchExecutor(searcher = { _, _, _ -> emptyList() })

        val report = executor.executeDetailed(listOf(phone), input, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, report.executions.single().state)
        assertTrue(report.evidence.isEmpty())
    }

    @Test
    fun nameAndUsernameSeedsUseTheBoundedSearchOutcomeSeam() = runBlocking {
        val name = userSeed(TypedSeedKind.Name, "Jane Example")
        val username = userSeed(TypedSeedKind.Username, "Sample_User")
        val seenInputs = mutableMapOf<TypedSeedKind, IdentityInput>()
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { seed, scopedInput, _ ->
                seenInputs[seed.kind] = scopedInput
                PublicSearchDiscoveryService.SearchOutcome.Success(
                    listOf(
                        PublicSearchResult(
                            title = "${seed.exactValue} result",
                            snippet = "Indexed public result",
                            url = "https://search.example.test/${seed.kind.name.lowercase()}",
                            query = "\"${seed.exactValue}\"",
                            source = "Fixture",
                            score = 0.8f
                        )
                    )
                )
            }
        )

        val nameReport = executor.executeDetailed(
            seeds = listOf(name),
            input = IdentityInput(fullName = "Jane Example"),
            scanId = scanId
        )
        val usernameReport = executor.executeDetailed(
            seeds = listOf(username),
            input = IdentityInput(fullName = "", usernames = listOf("Sample_User")),
            scanId = scanId
        )

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, nameReport.executions.single().state)
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, usernameReport.executions.single().state)
        assertEquals("Jane Example", seenInputs[TypedSeedKind.Name]?.fullName)
        assertEquals(listOf("Sample_User"), seenInputs[TypedSeedKind.Username]?.usernames)
        assertTrue(nameReport.evidence.any { it.value == "https://search.example.test/name" })
        assertTrue(usernameReport.evidence.any { it.value == "https://search.example.test/username" })
    }

    @Test
    fun explicitUnavailableSearchOutcomeRemainsInspectableAndTerminalFailure() = runBlocking {
        val email = evidenceSeed(TypedSeedKind.Email, "down@example.test")
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { _, _, _ ->
                io.dossier.app.data.web.PublicSearchDiscoveryService.SearchOutcome.Unavailable(
                    "fixture provider unavailable"
                )
            }
        )

        val report = executor.executeDetailed(listOf(email), input, scanId)

        assertEquals(
            TypedSeedPublicFetchExecutor.ExecutionState.Unavailable,
            report.executions.single().state
        )
        assertTrue(report.executions.single().reason.orEmpty().contains("fixture provider"))
        assertTrue(report.evidence.single().state == EvidenceState.Unavailable)
    }

    @Test
    fun unavailableSearchPreservesSafeSeedSourceWithoutTreatingExactValueAsUrl() = runBlocking {
        val seed = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "down@example.test",
            exactValue = "down@example.test",
            normalizedValue = "down@example.test",
            isVerified = true,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
            evidenceState = EvidenceState.Verified,
            evidenceIds = listOf("profile-email"),
            sourceUrl = "https://profile.example.test/source"
        )
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { _, _, _ ->
                PublicSearchDiscoveryService.SearchOutcome.Unavailable("fixture unavailable")
            }
        )

        val record = executor.executeDetailed(listOf(seed), input, scanId).evidence.single()

        assertEquals(seed.sourceUrl, record.sourceUrl)
        assertEquals(listOf(seed.sourceUrl), record.sourceUrls)
        assertTrue(record.discoveryPath.contains(seed.sourceUrl))
        assertFalse(seed.exactValue in record.sourceUrls)
        assertTrue(record.sourceUrls.all(DiscoveryHttpPolicy::isSafePublicHttpUrl))
    }

    @Test
    fun searchResultsAreValidatedDeduplicatedAndRetainBoundedProvenance() = runBlocking {
        val seed = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "target@example.test",
            exactValue = " Target@EXAMPLE.TEST ",
            normalizedValue = "target@example.test",
            isVerified = true,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
            evidenceState = EvidenceState.Verified,
            evidenceIds = listOf("seed-evidence"),
            sourceUrl = "https://profile.example.test/source",
            discoveryPath = listOf("https://profile.example.test/source")
        )
        val long = "x".repeat(10_000)
        val duplicate = PublicSearchResult(
            title = "short",
            snippet = "duplicate",
            url = "https://social.example.test/profile?utm_source=fixture",
            query = "\"target@example.test\"",
            source = "Fixture A",
            score = 0.4f
        )
        val best = PublicSearchResult(
            title = long,
            snippet = long,
            url = "https://social.example.test/profile?utm_medium=fixture#fragment",
            query = long,
            source = "Fixture B",
            score = 0.9f,
            providerCount = 99,
            directlyVerified = true,
            verificationNote = long,
            pivotSeedKind = TypedSeedKind.Email,
            pivotExactValue = long,
            pivotNormalizedValue = "target@example.test",
            pivotEvidenceIds = listOf("pivot-evidence", " "),
            pivotDiscoveryPath = listOf(long, "https://pivot.example.test/source"),
            pivotStage = long,
            pivotSourceUrl = "https://pivot.example.test/source",
            contentHashSha256 = "hash-value"
        )
        val executor = TypedSeedPublicFetchExecutor(
            searcher = { _, _, _ ->
                listOf(
                    duplicate,
                    best,
                    best.copy(url = "http://127.0.0.1/private"),
                    best.copy(score = Float.NaN),
                    best.copy(score = Float.POSITIVE_INFINITY),
                    best.copy(query = "", source = "Fixture C", url = "https://other.example.test/valid")
                )
            }
        )

        val report = executor.executeDetailed(listOf(seed), input, scanId)
        val evidence = report.evidence.single()

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, report.executions.single().state)
        assertEquals("https://social.example.test/profile?utm_medium=fixture#fragment", evidence.value)
        assertEquals(EvidenceState.Observed, evidence.state)
        assertEquals(FindingAttribution.Unconfirmed, evidence.attribution)
        assertEquals(listOf("seed-evidence", "pivot-evidence"), evidence.supportingEvidenceIds)
        assertEquals("hash-value", evidence.contentHashSha256)
        assertTrue(evidence.sourceUrls.isNotEmpty())
        assertTrue(evidence.sourceUrls.none { it.contains("@") || it.any(Char::isDigit) && !it.contains("://") })
        assertTrue(evidence.sourceUrls.all { DiscoveryHttpPolicy.isSafePublicHttpUrl(it) })
        assertTrue(evidence.discoveryPath.size <= Evidence.MAX_DISCOVERY_PATH_STEPS)
        assertTrue(evidence.signals.all { it.length <= 1_024 })
        assertTrue(evidence.signals.any { it.contains("Query:") })
        assertTrue(evidence.signals.any { it.contains("Seed exact value:") })
        assertEquals(" Target@EXAMPLE.TEST ", report.executions.single().seed.exactValue)
        assertEquals("target@example.test", report.executions.single().seed.normalizedValue)
    }

    @Test
    fun parentCancellationDoesNotEmitLateSearchEvidence() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val email = evidenceSeed(TypedSeedKind.Email, "cancel@example.test")
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { _, _, _ ->
                started.complete(Unit)
                release.await()
                PublicSearchDiscoveryService.SearchOutcome.Success(
                    listOf(
                        PublicSearchResult(
                            title = "late",
                            snippet = "late",
                            url = "https://late.example.test/profile",
                            query = "\"cancel@example.test\"",
                            source = "Fixture",
                            score = 0.9f
                        )
                    )
                )
            }
        )
        val job = async { executor.executeDetailed(listOf(email), input, scanId) }

        started.await()
        job.cancel()
        release.complete(Unit)

        try {
            job.await()
            throw AssertionError("cancelled search must not return a report")
        } catch (_: CancellationException) {
            assertTrue(job.isCancelled)
        }
    }

    private fun userSeed(kind: TypedSeedKind, value: String): TypedSeed = TypedSeed(
        kind = kind,
        value = when (kind) {
            TypedSeedKind.Domain -> value.lowercase().removeSuffix(".")
            TypedSeedKind.Username -> value.removePrefix("@").lowercase()
            else -> value
        },
        exactValue = value,
        normalizedValue = when (kind) {
            TypedSeedKind.Domain -> value.lowercase().removeSuffix(".")
            TypedSeedKind.Username -> value.removePrefix("@").lowercase()
            else -> value
        },
        origin = TypedSeedOrigin.UserInput,
        sourceClassification = ExposureSourceClassification.USER_IMPORTED,
        evidenceState = EvidenceState.Observed
    )

    private fun evidenceSeed(kind: TypedSeedKind, value: String): TypedSeed = TypedSeed(
        kind = kind,
        value = value,
        exactValue = value,
        normalizedValue = value,
        isVerified = true,
        origin = TypedSeedOrigin.Evidence,
        sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
        evidenceState = EvidenceState.Verified,
        evidenceIds = listOf("seed-id-1"),
        sourceUrl = "https://source.example.test/page"
    )

    private fun executor(
        fetch: suspend (io.dossier.app.domain.discovery.ProviderDefinition, String, ScanId, Int) -> ProviderExecutionResult
    ) = TypedSeedPublicFetchExecutor(
        fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { provider, url, id, maxBodyChars ->
            fetch(provider, url, id, maxBodyChars)
        }
    )

    private fun present(url: String, body: String, contentType: String? = null) = ProviderExecutionResult(
        decision = ProviderResponseDecision(ProviderVerificationState.Present, "fixture present"),
        statusCode = 200,
        requestedUrl = url,
        finalUrl = url,
        bodyText = body,
        latencyMs = 1,
        attemptCount = 1,
        contentType = contentType
    )

    private fun error(message: String): ProviderExecutionResult =
        throw IllegalStateException(message)
}
