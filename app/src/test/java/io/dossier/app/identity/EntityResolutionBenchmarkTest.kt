package io.dossier.app.identity

import io.dossier.app.domain.identity.CorrelationFeature
import io.dossier.app.domain.identity.EntityResolutionBenchmark
import io.dossier.app.domain.identity.EntityResolutionBenchmarkFixtures
import io.dossier.app.domain.identity.EntityResolutionBenchmarkMetrics
import io.dossier.app.domain.identity.EntityResolutionCalibrationArtifact
import io.dossier.app.domain.identity.EntityResolutionCalibrationLoadResult
import io.dossier.app.domain.identity.EntityResolutionCalibrationLoader
import io.dossier.app.domain.identity.EntityResolutionCorpusKind
import io.dossier.app.domain.identity.EntityResolutionEvaluationSplit
import io.dossier.app.domain.identity.EntityResolutionPolicy
import io.dossier.app.domain.identity.EntityResolverV2
import io.dossier.app.domain.identity.ResolutionBand
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResolutionBenchmarkTest {
    private val trainingDigest = "f".repeat(64)
    private val authorizationDigest = "e".repeat(64)

    @Test
    fun syntheticCorpusIsDeterministicAndReportsIndependentErrorRates() {
        val corpus = EntityResolutionBenchmarkFixtures.syntheticCorpus()
        val secondRead = EntityResolutionBenchmarkFixtures.syntheticCorpus()
        val reordered = corpus.copy(cases = corpus.cases.reversed())
        val evaluation = EntityResolutionBenchmark.evaluate(corpus)

        assertEquals(corpus.digest, secondRead.digest)
        assertEquals(corpus.digest, reordered.digest)
        val findingChanged = corpus.copy(
            cases = corpus.cases.mapIndexed { index, testCase ->
                if (index != 0) testCase else testCase.copy(
                    profile = testCase.profile.copy(
                        findings = listOf(
                            io.dossier.app.domain.model.Finding(
                                type = io.dossier.app.domain.model.FindingType.Email,
                                value = "different@example.test",
                                sourceUrl = testCase.profile.candidate.url,
                                evidenceSnippet = "synthetic fixture",
                                confidence = 0.8f,
                                risk = io.dossier.app.domain.model.RiskLevel.Low,
                                remediation = "Review"
                            )
                        )
                    )
                )
            }
        )
        assertTrue(corpus.digest != findingChanged.digest)
        assertEquals(7, evaluation.metrics.total)
        assertEquals(2, evaluation.metrics.truePositives)
        assertEquals(1, evaluation.metrics.falsePositives)
        assertEquals(1, evaluation.metrics.falseNegatives)
        assertEquals(2, evaluation.metrics.trueNegatives)
        assertEquals(1, evaluation.metrics.correctlyUnverifiable)
        assertEquals(0, evaluation.metrics.unsafeUnverifiablePositives)
        assertTrue(evaluation.metrics.falsePositiveRate in 0.0..1.0)
        assertTrue(evaluation.metrics.falseNegativeRate in 0.0..1.0)
        assertTrue(evaluation.metrics.unverifiableAccuracy in 0.0..1.0)
        // Synthetic fixture results are regression evidence, not a scientific estimate.
        assertEquals(EntityResolutionCorpusKind.SYNTHETIC, corpus.kind)
    }

    @Test
    fun corpusDigestBindsHeldOutAndAuthorizationMetadata() {
        val regression = EntityResolutionBenchmarkFixtures.syntheticCorpus()
        val heldOut = regression.copy(
            kind = EntityResolutionCorpusKind.CONSENTED,
            evaluationSplit = EntityResolutionEvaluationSplit.HELD_OUT,
            trainingCorpusDigest = trainingDigest,
            authorizationRecordDigest = authorizationDigest
        )

        assertNotEquals(regression.digest, heldOut.digest)
        assertEquals(EntityResolutionEvaluationSplit.HELD_OUT, heldOut.evaluationSplit)
        assertEquals(trainingDigest, heldOut.trainingCorpusDigest)
        assertEquals(authorizationDigest, heldOut.authorizationRecordDigest)
    }

    @Test
    fun fixturesPreserveContributionAndContradictionEvidence() {
        val corpus = EntityResolutionBenchmarkFixtures.syntheticCorpus()
        val positive = corpus.cases.first { it.id == "belongs-independent-signals" }
        val positiveResult = EntityResolverV2.resolve(positive.input, positive.profile)
        assertTrue(positiveResult.supporting.size >= 3)
        assertTrue(positiveResult.supporting.all { it.explanation.isNotBlank() })

        val contradiction = corpus.cases.first { it.id == "does-not-belong-name-contradiction" }
        val contradictionResult = EntityResolverV2.resolve(contradiction.input, contradiction.profile)
        assertEquals(ResolutionBand.Conflicting, contradictionResult.band)
        assertTrue(contradictionResult.contradicting.isNotEmpty())
        assertTrue(contradictionResult.contradicting.all { it.evidenceIds.isEmpty() || it.evidenceIds.all(String::isNotBlank) })
    }

    @Test
    fun calibrationArtifactRoundTripsButSyntheticArtifactCannotAlterProduction() {
        val evaluation = EntityResolutionBenchmark.evaluate(EntityResolutionBenchmarkFixtures.syntheticCorpus())
        val artifact = EntityResolutionCalibrationArtifact.fromEvaluation(
            evaluation = evaluation,
            policy = EntityResolutionPolicy(
                lowScore = 0.20,
                corroboratedMediumScore = 0.99,
                mediumScore = 0.99,
                highScore = 0.99
            ),
            source = "synthetic-regression-only"
        )
        val loaded = EntityResolutionCalibrationLoader.loadOrNull(artifact.toJson(), evaluation.corpusDigest)

        assertNotNull(loaded)
        assertNull(loaded!!.productionPolicyOrNull(evaluation.corpusDigest))

        val testCase = evaluation.corpus.cases.first { it.id == "belongs-independent-signals" }
        val defaultResult = EntityResolverV2.resolve(testCase.input, testCase.profile)
        val syntheticResult = EntityResolverV2.resolve(testCase.input, testCase.profile, loaded)
        assertEquals(defaultResult.band, syntheticResult.band)
    }

    @Test
    fun validConsentedArtifactChangesOnlyConfiguredThresholds() {
        val artifact = EntityResolutionCalibrationArtifact(
            schemaVersion = EntityResolutionCalibrationArtifact.SCHEMA_VERSION,
            resolverVersion = EntityResolverV2.RESOLVER_VERSION,
            benchmarkVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
            corpusId = "consented-fixture",
            corpusVersion = "2026-08-24",
            corpusKind = EntityResolutionCorpusKind.CONSENTED,
            corpusDigest = "a".repeat(64),
            deterministicSeed = 7L,
            sampleCount = 200,
            positiveCaseCount = 100,
            negativeCaseCount = 100,
            unverifiableCaseCount = 0,
            metrics = EntityResolutionBenchmarkMetrics(
                truePositives = 100,
                falsePositives = 0,
                falseNegatives = 0,
                trueNegatives = 100,
                correctlyUnverifiable = 0,
                unsafeUnverifiablePositives = 0,
                total = 200
            ),
            policy = EntityResolutionPolicy(
                lowScore = 0.20,
                corroboratedMediumScore = 0.95,
                mediumScore = 0.95,
                highScore = 0.95
            ),
            source = "consented-holdout-fixture",
            evaluationSplit = EntityResolutionEvaluationSplit.HELD_OUT,
            trainingCorpusDigest = trainingDigest,
            authorizationRecordDigest = authorizationDigest
        )
        val loaded = EntityResolutionCalibrationLoader.loadOrNull(
            artifact.toJson(),
            artifact.corpusDigest,
            trainingDigest,
            authorizationDigest
        )
        assertNotNull(loaded)
        assertNotNull(
            loaded!!.productionPolicyOrNull(
                artifact.corpusDigest,
                trainingDigest,
                authorizationDigest
            )
        )

        val input = IdentityInput(
            fullName = "Jane Example",
            primaryUsername = "jane_example"
        )
        val profile = profile(
            username = "jane_example",
            url = "https://github.com/jane_example",
            verified = true,
            displayName = "Jane Example"
        )
        assertEquals(ResolutionBand.High, EntityResolverV2.resolve(input, profile).band)
        assertEquals(
            ResolutionBand.High,
            EntityResolverV2.resolve(input, profile, loaded).band
        )
        assertEquals(
            ResolutionBand.Low,
            EntityResolverV2.resolve(
                input,
                profile,
                loaded,
                expectedCorpusDigest = artifact.corpusDigest,
                expectedTrainingCorpusDigest = trainingDigest,
                expectedAuthorizationRecordDigest = authorizationDigest
            ).band
        )
    }

    @Test
    fun consentedRegressionArtifactCannotActivateProductionPolicy() {
        val artifact = EntityResolutionCalibrationArtifact(
            schemaVersion = EntityResolutionCalibrationArtifact.SCHEMA_VERSION,
            resolverVersion = EntityResolverV2.RESOLVER_VERSION,
            benchmarkVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
            corpusId = "consented-regression-fixture",
            corpusVersion = "2026-08-24",
            corpusKind = EntityResolutionCorpusKind.CONSENTED,
            corpusDigest = "a".repeat(64),
            deterministicSeed = 8L,
            sampleCount = 200,
            positiveCaseCount = 100,
            negativeCaseCount = 100,
            unverifiableCaseCount = 0,
            metrics = EntityResolutionBenchmarkMetrics(100, 0, 0, 100, 0, 0, 200),
            policy = EntityResolutionPolicy.DEFAULT,
            source = "consented-regression-fixture",
            authorizationRecordDigest = authorizationDigest
        )

        assertNull(
            artifact.productionPolicyOrNull(
                expectedCorpusDigest = artifact.corpusDigest,
                expectedAuthorizationRecordDigest = authorizationDigest
            )
        )
    }

    @Test
    fun calibrationLoaderBindsHeldOutProvenanceDigests() {
        val artifact = EntityResolutionCalibrationArtifact(
            schemaVersion = EntityResolutionCalibrationArtifact.SCHEMA_VERSION,
            resolverVersion = EntityResolverV2.RESOLVER_VERSION,
            benchmarkVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
            corpusId = "consented-fixture",
            corpusVersion = "2026-08-24",
            corpusKind = EntityResolutionCorpusKind.CONSENTED,
            corpusDigest = "a".repeat(64),
            deterministicSeed = 9L,
            sampleCount = 200,
            positiveCaseCount = 100,
            negativeCaseCount = 100,
            unverifiableCaseCount = 0,
            metrics = EntityResolutionBenchmarkMetrics(100, 0, 0, 100, 0, 0, 200),
            policy = EntityResolutionPolicy.DEFAULT,
            source = "consented-holdout-fixture",
            evaluationSplit = EntityResolutionEvaluationSplit.HELD_OUT,
            trainingCorpusDigest = trainingDigest,
            authorizationRecordDigest = authorizationDigest
        )

        val wrongTraining = EntityResolutionCalibrationLoader.load(
            artifact.toJson(),
            expectedCorpusDigest = artifact.corpusDigest,
            expectedTrainingCorpusDigest = "d".repeat(64),
            expectedAuthorizationRecordDigest = authorizationDigest
        )
        val wrongAuthorization = EntityResolutionCalibrationLoader.load(
            artifact.toJson(),
            expectedCorpusDigest = artifact.corpusDigest,
            expectedTrainingCorpusDigest = trainingDigest,
            expectedAuthorizationRecordDigest = "c".repeat(64)
        )
        val missingProvenance = EntityResolutionCalibrationLoader.load(
            artifact.toJson(),
            expectedCorpusDigest = artifact.corpusDigest
        )

        assertTrue(wrongTraining is EntityResolutionCalibrationLoadResult.Rejected)
        assertTrue(wrongAuthorization is EntityResolutionCalibrationLoadResult.Rejected)
        assertTrue(missingProvenance is EntityResolutionCalibrationLoadResult.Rejected)
    }

    @Test
    fun loaderRejectsWrongDigestAndResolverVersion() {
        val artifact = EntityResolutionCalibrationArtifact(
            schemaVersion = EntityResolutionCalibrationArtifact.SCHEMA_VERSION,
            resolverVersion = EntityResolverV2.RESOLVER_VERSION,
            benchmarkVersion = EntityResolutionBenchmark.BENCHMARK_VERSION,
            corpusId = "consented-fixture",
            corpusVersion = "1",
            corpusKind = EntityResolutionCorpusKind.CONSENTED,
            corpusDigest = "b".repeat(64),
            deterministicSeed = 1L,
            sampleCount = 200,
            positiveCaseCount = 100,
            negativeCaseCount = 100,
            unverifiableCaseCount = 0,
            metrics = EntityResolutionBenchmarkMetrics(100, 0, 0, 100, 0, 0, 200),
            policy = EntityResolutionPolicy.DEFAULT,
            source = "consented-fixture",
            evaluationSplit = EntityResolutionEvaluationSplit.HELD_OUT,
            trainingCorpusDigest = trainingDigest,
            authorizationRecordDigest = authorizationDigest
        )

        val wrongDigest = EntityResolutionCalibrationLoader.load(
            artifact.toJson(),
            expectedCorpusDigest = "c".repeat(64),
            expectedTrainingCorpusDigest = trainingDigest,
            expectedAuthorizationRecordDigest = authorizationDigest
        )
        assertTrue(wrongDigest is EntityResolutionCalibrationLoadResult.Rejected)

        val wrongVersionJson = artifact.toJson().replace(
            EntityResolverV2.RESOLVER_VERSION,
            "entity-resolver-v1"
        )
        val wrongVersion = EntityResolutionCalibrationLoader.load(wrongVersionJson)
        assertTrue(wrongVersion is EntityResolutionCalibrationLoadResult.Rejected)

        val legacySchemaJson = artifact.toJson().replace(
            "\"schemaVersion\":${EntityResolutionCalibrationArtifact.SCHEMA_VERSION}",
            "\"schemaVersion\":1"
        )
        val legacySchema = EntityResolutionCalibrationLoader.load(legacySchemaJson)
        assertTrue(legacySchema is EntityResolutionCalibrationLoadResult.Rejected)
    }

    @Test
    fun calibratedContradictionWeightsCannotDisableNegativeEvidence() {
        val displayNameDisabled = runCatching {
            EntityResolutionPolicy(
                featureWeights = mapOf(CorrelationFeature.ConflictingDisplayName to 0.0)
            )
        }.exceptionOrNull()
        val websiteDisabled = runCatching {
            EntityResolutionPolicy(
                featureWeights = mapOf(CorrelationFeature.ConflictingPersonalWebsite to -0.0)
            )
        }.exceptionOrNull()

        assertTrue(displayNameDisabled is IllegalArgumentException)
        assertTrue(websiteDisabled is IllegalArgumentException)
    }

    @Test
    fun calibratedContradictionWeightsRemainNegativeAndConfigurable() {
        val policy = EntityResolutionPolicy(
            featureWeights = mapOf(
                CorrelationFeature.ConflictingDisplayName to -0.10,
                CorrelationFeature.ConflictingPersonalWebsite to -0.20
            )
        )

        assertEquals(-0.10, policy.weight(CorrelationFeature.ConflictingDisplayName, -0.45), 0.0)
        assertEquals(-0.20, policy.weight(CorrelationFeature.ConflictingPersonalWebsite, -0.45), 0.0)
    }

    private fun profile(
        username: String,
        url: String,
        verified: Boolean,
        displayName: String? = null
    ) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = username,
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = if (verified) 0.9f else 0.5f
        ),
        exists = true,
        httpStatus = 200,
        displayName = displayName,
        bio = null,
        links = emptyList(),
        extractedText = displayName.orEmpty(),
        findings = emptyList(),
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = if (verified) "verified" else "review"
    )
}
