package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TypedSeedFrontierTest {
    private val roots = mutableListOf<Path>()
    private val config = TypedSeedFrontierConfig(
        maxDepth = 4,
        maxTotalSeeds = 12,
        perKindBudgets = TypedSeedFrontierConfig.defaultBudgets()
    )
    private val plan = "a".repeat(64)

    @After
    fun tearDown() {
        roots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() }
            }
        }
    }

    @Test
    fun encryptedRoundTripRetainsExactValuesAndEvidenceWithoutPlaintextEnvelope() {
        val request = uuid()
        val owner = uuid()
        val generation = uuid()
        val store = store(request)
        val frontier = frontier(request, owner, generation)
        val exact = "jane.secret@example.test"
        assertTrue(frontier.offer(userSeed(TypedSeedKind.Email, exact)))
        frontier.mergeEvidence(
            EvidenceCollection(
                evidence = listOf(
                    Evidence(
                        id = "email-evidence",
                        kind = EvidenceKind.Email,
                        value = exact,
                        sourceUrl = "https://profile.example.test/jane",
                        state = EvidenceState.Verified,
                        risk = RiskLevel.High,
                        sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE
                    )
                )
            )
        )

        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, owner, generation, plan))
        val envelope = store.frontierFileForTesting().readBytes().toString(Charsets.UTF_8)
        assertFalse("exact seed must stay encrypted", envelope.contains(exact))
        assertFalse("evidence value must stay encrypted", envelope.contains("email-evidence"))

        val loaded = store.load(config, owner, generation, plan)
        assertTrue(loaded is TypedSeedFrontierLoadResult.Available)
        val restored = (loaded as TypedSeedFrontierLoadResult.Available).frontier
        assertEquals(exact, restored.entries.single().seed.exactValue)
        assertEquals(listOf("email-evidence"), restored.evidence.evidence.map(Evidence::id))
    }

    @Test
    fun requestPlanOwnerAndGenerationAreIndependentBindings() {
        val request = uuid()
        val owner = uuid()
        val generation = uuid()
        val store = store(request)
        val frontier = frontier(request, owner, generation)
        frontier.offer(userSeed(TypedSeedKind.Url, "https://example.test/a"))
        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, owner, generation, plan))

        assertEquals(
            TypedSeedFrontierLoadResult.Unavailable,
            store.load(config, owner, generation, "b".repeat(64))
        )
        assertEquals(
            TypedSeedFrontierLoadResult.StaleOwner,
            store.load(config, uuid(), generation, plan)
        )
        assertEquals(
            TypedSeedFrontierLoadResult.StaleOwner,
            store.load(config, owner, uuid(), plan)
        )
        assertEquals(
            TypedSeedFrontierWriteResult.StaleOwner,
            store.save(frontier, uuid(), generation, plan)
        )
        assertEquals(
            TypedSeedFrontierWriteResult.Invalid,
            store.save(frontier, owner, generation, "b".repeat(64))
        )

        val otherRequest = uuid()
        val otherStore = store(otherRequest)
        val otherFrontier = frontier(otherRequest, owner, generation)
        assertEquals(
            TypedSeedFrontierWriteResult.StaleOwner,
            store.save(otherFrontier, owner, generation, plan)
        )
        assertEquals(TypedSeedFrontierLoadResult.Missing, otherStore.load(config, owner, generation, plan))
    }

    @Test
    fun ownerRebindReencryptsOnlyMatchingGenerationAndPlan() {
        val request = uuid()
        val oldOwner = uuid()
        val newOwner = uuid()
        val generation = uuid()
        val store = store(request)
        val frontier = frontier(request, oldOwner, generation)
        frontier.offer(userSeed(TypedSeedKind.Url, "https://example.test/a"))
        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, oldOwner, generation, plan))

        assertEquals(
            TypedSeedFrontierWriteResult.Saved,
            store.rebindOwner(newOwner, generation, expectedOwnerId = oldOwner, planFingerprint = plan)
        )
        val rebound = store.load(config, newOwner, generation, plan)
        assertTrue(rebound is TypedSeedFrontierLoadResult.Available)
        assertEquals(newOwner, (rebound as TypedSeedFrontierLoadResult.Available).frontier.ownerId)
        assertEquals(
            TypedSeedFrontierLoadResult.StaleOwner,
            store.load(config, oldOwner, generation, plan)
        )
        assertEquals(
            TypedSeedFrontierWriteResult.StaleOwner,
            store.rebindOwner(uuid(), uuid(), expectedOwnerId = newOwner, planFingerprint = plan)
        )
    }

    @Test
    fun expiredEnvelopeCannotBeReboundToAnotherOwner() {
        var clock = 1_000L
        val request = uuid()
        val oldOwner = uuid()
        val newOwner = uuid()
        val generation = uuid()
        val root = Files.createTempDirectory("typed-frontier-expiry-")
        roots.add(root)
        val store = TypedSeedFrontierStore(
            rootDir = root.toFile(),
            requestId = request,
            crypto = AesCheckpointCrypto(),
            nowMillis = { clock }
        )
        val frontier = frontier(request, oldOwner, generation)
        assertTrue(frontier.offer(userSeed(TypedSeedKind.Url, "https://example.test/expired")))
        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, oldOwner, generation, plan))

        clock += 7L * 24L * 60L * 60L * 1_000L + 1L

        assertEquals(
            TypedSeedFrontierWriteResult.Invalid,
            store.rebindOwner(newOwner, generation, expectedOwnerId = oldOwner, planFingerprint = plan)
        )
        assertEquals(
            TypedSeedFrontierLoadResult.Unavailable,
            store.load(config, oldOwner, generation, plan)
        )
    }

    @Test
    fun inFlightEntriesRecoverAndCancellationReleasesPendingWork() {
        val request = uuid()
        val owner = uuid()
        val generation = uuid()
        val frontier = frontier(request, owner, generation)
        val seed = userSeed(TypedSeedKind.Url, "https://example.test/a")
        assertTrue(frontier.offer(seed))
        val key = frontier.entries.single().key
        assertNotNull(frontier.begin(key))
        assertEquals(1, frontier.inFlightCount)
        assertTrue(frontier.releaseInFlight(key))
        assertEquals(1, frontier.pendingCount)
        assertFalse(frontier.releaseInFlight(key))

        assertNotNull(frontier.begin(key))
        val recovered = TypedSeedFrontier(
            requestId = request,
            config = config,
            ownerId = owner,
            generation = generation,
            planFingerprint = plan,
            state = frontier.snapshot()
        )
        assertEquals(1, recovered.pendingCount)
        assertEquals(TypedSeedFrontierEntryState.Pending, recovered.entries.single().state)
    }

    @Test
    fun pendingPrioritizesVerifiedHighEntropySeedsAndKeepsInsertionOrderForTies() {
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = config
        )
        val verifiedUrl = userSeed(TypedSeedKind.Url, "https://example.test/verified").copy(
            evidenceState = EvidenceState.Verified,
            isVerified = true,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            evidenceIds = listOf("evidence-url"),
            sourceUrl = "https://example.test/source"
        )
        val observedUrlA = userSeed(TypedSeedKind.Url, "https://example.test/observed-a")
        val observedUrlB = userSeed(TypedSeedKind.Url, "https://example.test/observed-b")
        val firstDomain = userSeed(TypedSeedKind.Domain, "first.example.test")
        val secondDomain = userSeed(TypedSeedKind.Domain, "second.example.test")

        assertTrue(frontier.offer(firstDomain))
        assertTrue(frontier.offer(secondDomain))
        assertTrue(frontier.offer(observedUrlA))
        assertTrue(frontier.offer(observedUrlB))
        assertTrue(frontier.offer(verifiedUrl))

        assertEquals(
            listOf(
                "https://example.test/verified",
                "https://example.test/observed-a",
                "https://example.test/observed-b",
                "first.example.test",
                "second.example.test"
            ),
            frontier.pending().map { it.seed.exactValue }
        )
    }

    @Test
    fun lateExecutableSeedDisplacesLowPriorityEntryAfterThirtyTwoMixedAdmissions() {
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = TypedSeedFrontierConfig(
                maxDepth = 4,
                maxTotalSeeds = 32,
                perKindBudgets = TypedSeedKind.entries.associateWith { 128 }
            )
        )

        repeat(8) { index ->
            assertTrue(frontier.offer(userSeed(TypedSeedKind.Name, "Low Priority Name $index")))
            assertTrue(frontier.offer(userSeed(TypedSeedKind.Username, "low_priority_user_$index")))
            assertTrue(frontier.offer(userSeed(TypedSeedKind.Photo, "content://media/low-priority-$index")))
            assertTrue(frontier.offer(userSeed(TypedSeedKind.Location, "Low Priority Location $index")))
        }
        assertEquals(32, frontier.admittedCount)

        val executable = userSeed(TypedSeedKind.Url, "https://priority.example.test/profile").copy(
            isVerified = true,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            evidenceIds = listOf("verified-url"),
            sourceUrl = "https://source.example.test/profile"
        )
        assertTrue(frontier.offer(executable))

        assertEquals(32, frontier.admittedCount)
        assertTrue(frontier.entries.any { it.seed.exactValue == executable.exactValue })
        assertFalse(frontier.entries.any { it.seed.exactValue == "Low Priority Name 0" })
        assertEquals(executable.exactValue, frontier.pending().first().seed.exactValue)
    }

    @Test
    fun fullFrontierRetainsBoundedRejectionReasonWhenWorkCannotBeEvicted() {
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = TypedSeedFrontierConfig(
                maxDepth = 4,
                maxTotalSeeds = 1,
                perKindBudgets = TypedSeedKind.entries.associateWith { 128 }
            )
        )
        val first = userSeed(TypedSeedKind.Url, "https://first.example.test/profile")
        val second = userSeed(TypedSeedKind.Url, "https://second.example.test/profile")

        assertTrue(frontier.offer(first))
        val firstKey = frontier.entries.single().key
        assertNotNull(frontier.begin(firstKey))
        assertFalse(frontier.offer(second))

        val rejection = frontier.rejectionDiagnosticsSnapshot.last()
        assertEquals(TypedSeedKind.Url, rejection.kind)
        assertEquals(second.exactValue, rejection.value)
        assertTrue(rejection.reason.contains("no pending lower-priority"))
        assertTrue(frontier.rejectionDiagnosticsSnapshot.size <= TypedSeedFrontier.MAX_REJECTION_DIAGNOSTICS)
    }

    @Test
    fun equalPriorityActionableSeedReplacesUnavailableButNotPendingWork() {
        val pendingFrontier = TypedSeedFrontier(
            requestId = uuid(),
            config = TypedSeedFrontierConfig(
                maxDepth = 4,
                maxTotalSeeds = 1,
                perKindBudgets = TypedSeedKind.entries.associateWith { 128 }
            )
        )
        val pendingDocument = userSeed(TypedSeedKind.Document, "https://example.test/pending")
        val actionableUrl = userSeed(TypedSeedKind.Url, "https://example.test/actionable")
        assertTrue(pendingFrontier.offer(pendingDocument))
        assertFalse(pendingFrontier.offer(actionableUrl))
        assertEquals(pendingDocument.exactValue, pendingFrontier.entries.single().seed.exactValue)

        val unavailableFrontier = TypedSeedFrontier(
            requestId = uuid(),
            config = TypedSeedFrontierConfig(
                maxDepth = 4,
                maxTotalSeeds = 1,
                perKindBudgets = TypedSeedKind.entries.associateWith { 128 }
            )
        )
        val unavailableDocument = userSeed(TypedSeedKind.Document, "https://example.test/unavailable")
        assertTrue(unavailableFrontier.offer(unavailableDocument))
        val unavailableKey = unavailableFrontier.entries.single().key
        assertTrue(unavailableFrontier.unavailable(unavailableKey, "provider failed"))
        assertTrue(unavailableFrontier.offer(actionableUrl))
        assertEquals(actionableUrl.exactValue, unavailableFrontier.entries.single().seed.exactValue)
        assertEquals(TypedSeedFrontierEntryState.Pending, unavailableFrontier.entries.single().state)
    }

    @Test
    fun executableHighEntropyEmailDoesNotCrowdOutActionableUrl() {
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = TypedSeedFrontierConfig(
                maxDepth = 4,
                maxTotalSeeds = 1,
                perKindBudgets = TypedSeedKind.entries.associateWith { 128 }
            )
        )
        val email = userSeed(TypedSeedKind.Email, "unsupported@example.test")
        val url = userSeed(TypedSeedKind.Url, "https://actionable.example.test/profile")

        assertTrue(frontier.offer(email))
        assertEquals(TypedSeedFrontierEntryState.Pending, frontier.entries.single().state)
        assertTrue(frontier.offer(url))

        assertEquals(url.exactValue, frontier.entries.single().seed.exactValue)
        assertEquals(TypedSeedFrontierEntryState.Pending, frontier.entries.single().state)
    }

    @Test
    fun pendingEntryAtRetryLimitBecomesTerminalUnavailable() {
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = config,
            ownerId = uuid(),
            generation = uuid(),
            planFingerprint = plan
        )
        val seed = userSeed(TypedSeedKind.Url, "https://example.test/retry")
        assertTrue(frontier.offer(seed))
        val key = frontier.entries.single().key

        repeat(TypedSeedFrontier.MAX_ATTEMPTS) {
            assertNotNull(frontier.begin(key))
            assertTrue(frontier.releaseInFlight(key))
        }
        assertNull(frontier.begin(key))
        assertEquals(0, frontier.pendingCount)
        assertEquals(1, frontier.unavailableCount)
        assertTrue(frontier.entries.single().unavailableReason.orEmpty().contains("maximum", ignoreCase = true))
    }

    @Test
    fun executableEmailIsPersistedAsPendingAndTombstoneBlocksLateSave() {
        val request = uuid()
        val owner = uuid()
        val generation = uuid()
        val store = store(request)
        val frontier = frontier(request, owner, generation)
        assertTrue(frontier.offer(userSeed(TypedSeedKind.Email, "person@example.test")))
        assertEquals(TypedSeedFrontierEntryState.Pending, frontier.entries.single().state)
        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, owner, generation, plan))

        val loaded = store.load(config, owner, generation, plan)
        assertTrue(loaded is TypedSeedFrontierLoadResult.Available)
        assertEquals(
            TypedSeedFrontierEntryState.Pending,
            (loaded as TypedSeedFrontierLoadResult.Available).frontier.entries.single().state
        )

        assertTrue(store.clear())
        assertEquals(TypedSeedFrontierWriteResult.Tombstoned, store.save(frontier, owner, generation, plan))
        assertEquals(TypedSeedFrontierLoadResult.Unavailable, store.load(config, owner, generation, plan))
    }

    @Test
    fun malformedEnvelopeFailsClosedWithoutReplacingIt() {
        val request = uuid()
        val owner = uuid()
        val generation = uuid()
        val store = store(request)
        val frontier = frontier(request, owner, generation)
        frontier.offer(userSeed(TypedSeedKind.Url, "https://example.test/a"))
        assertEquals(TypedSeedFrontierWriteResult.Saved, store.save(frontier, owner, generation, plan))
        val file = store.frontierFileForTesting()
        val malformed = "not-json"
        file.writeText(malformed)

        assertEquals(TypedSeedFrontierLoadResult.Unavailable, store.load(config, owner, generation, plan))
        assertEquals(malformed, file.readText())
    }

    @Test
    fun recursiveFixtureFollowsUrlToUrlThenExactEmailDocumentAndArchive() = runBlocking {
        val urlA = "https://profile.example.test/a"
        val urlB = "https://profile.example.test/b"
        val document = "https://docs.example.test/jane.txt"
        val archive = "https://web.archive.org/web/20240101000000id_/https://profile.example.test/old"
        val email = "jane.recursive@example.test"
        val input = io.dossier.app.domain.model.IdentityInput(
            fullName = "Jane Example",
            profileUrls = listOf(urlA)
        )
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                val body = when (requested) {
                    urlA -> "<html><body><a href=\"$urlB\">next</a></body></html>"
                    urlB -> """
                        <html><body>
                        contact $email
                        <a href="$document">resume</a>
                        <a href="$archive">historical copy</a>
                        </body></html>
                    """.trimIndent()
                    document -> "<html><body>Document for $email</body></html>"
                    archive -> "<html><body>Archived Jane Example $email</body></html>"
                    else -> "<html><body>unexpected</body></html>"
                }
                ProviderExecutionResult(
                    decision = ProviderResponseDecision(ProviderVerificationState.Present, "fixture"),
                    statusCode = 200,
                    requestedUrl = requested,
                    finalUrl = requested,
                    bodyText = body,
                    latencyMs = 1,
                    attemptCount = 1,
                    contentType = "text/html"
                )
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                error("direct archive snapshots must be fetched without resolver")
            }
        )
        val frontier = TypedSeedFrontier(
            requestId = uuid(),
            config = config,
            nowMillis = { 1_000L }
        )
        assertTrue(frontier.offer(userSeed(TypedSeedKind.Url, urlA)))
        var cumulative = EvidenceCollection()
        var iterations = 0
        while (frontier.pendingCount > 0 && iterations++ < 16) {
            val entry = frontier.pending().first()
            val started = frontier.begin(entry.key) ?: continue
            val report = executor.executeDetailed(listOf(started.seed), input, ScanId("typed-frontier-recursion"))
            cumulative = EvidenceCollection(
                evidence = (cumulative.evidence + report.collection.evidence)
                    .distinctBy(Evidence::id),
                relationships = EvidenceRelationshipPolicy.normalize(
                    cumulative.relationships + report.collection.relationships
                )
            )
            frontier.mergeEvidence(report.collection)
            if (report.executions.single().state == TypedSeedPublicFetchExecutor.ExecutionState.Verified) {
                frontier.complete(started.key)
            } else {
                frontier.unavailable(started.key, report.executions.single().reason ?: "fixture unavailable")
            }
            TypedSeedEvidenceAdapter.fromCollection(
                collection = cumulative,
                input = input,
                config = config.admissionConfig()
            ).admittedSeeds.forEach(frontier::offer)
        }

        assertTrue("fixture should drain without a budget loop", iterations < 16)
        assertEquals(TypedSeedFrontierEntryState.Completed, frontier.entries.first { it.seed.exactValue == urlA }.state)
        assertEquals(TypedSeedFrontierEntryState.Completed, frontier.entries.first { it.seed.exactValue == urlB }.state)
        assertTrue(frontier.entries.none { it.seed.exactValue == email })
        assertEquals(TypedSeedFrontierEntryState.Completed, frontier.entries.first { it.seed.exactValue == document }.state)
        assertEquals(TypedSeedFrontierEntryState.Completed, frontier.entries.first { it.seed.exactValue == archive }.state)
        assertTrue(frontier.evidence.evidence.any { it.value == email && it.state == EvidenceState.Observed })
        assertTrue(frontier.evidence.evidence.none { it.value == email && it.state == EvidenceState.Verified })
        assertTrue(frontier.evidence.evidence.any { it.value == document && it.discoveryPath.containsAll(listOf(urlA, urlB)) })
        assertTrue(frontier.evidence.evidence.any { it.value == archive && it.sourceClassification == ExposureSourceClassification.PUBLIC_WEB })
    }

    private fun frontier(request: String, owner: String, generation: String): TypedSeedFrontier =
        TypedSeedFrontier(
            requestId = request,
            config = config,
            ownerId = owner,
            generation = generation,
            planFingerprint = plan
        )

    private fun userSeed(kind: TypedSeedKind, value: String): TypedSeed = TypedSeed(
        kind = kind,
        value = value,
        exactValue = value,
        normalizedValue = value,
        evidenceState = EvidenceState.Observed,
        origin = TypedSeedOrigin.UserInput,
        sourceClassification = ExposureSourceClassification.USER_IMPORTED
    )

    private fun store(request: String): TypedSeedFrontierStore {
        val root = Files.createTempDirectory("typed-frontier-test-")
        roots.add(root)
        return TypedSeedFrontierStore(root.toFile(), request, AesCheckpointCrypto())
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private class AesCheckpointCrypto : CheckpointCrypto {
        private val key = SecretKeySpec(ByteArray(32) { index -> (index + 11).toByte() }, "AES")
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return CheckpointCrypto.Encrypted(
                ivBase64 = Base64.getEncoder().encodeToString(iv),
                ciphertextBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
            )
        }

        override fun decrypt(
            ivBase64: String,
            ciphertextBase64: String,
            aad: ByteArray
        ): ByteArray? = runCatching {
            val iv = Base64.getDecoder().decode(ivBase64)
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }
}
