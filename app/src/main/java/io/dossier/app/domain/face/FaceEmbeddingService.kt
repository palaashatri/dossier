package io.dossier.app.domain.face

import android.content.Context
import android.net.Uri
import io.dossier.app.data.face.FaceAppearanceDescriptor
import io.dossier.app.data.face.FaceCorrelationCalibrationStore
import io.dossier.app.data.face.FaceCorrelationConsentStore
import io.dossier.app.data.face.FaceCorrelationDecision
import io.dossier.app.data.face.FaceCorrelationModelPack
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.data.face.FaceEmbedder
import io.dossier.app.data.face.FaceEmbeddingCalibrationStore
import io.dossier.app.data.face.FaceEmbeddingModelRunner
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.FaceEmbeddingThresholds
import io.dossier.app.data.face.YuNetSFaceCorrelationEngine
import io.dossier.app.domain.model.FaceComparisonBackend
import io.dossier.app.domain.model.FaceComparisonCalibrationState
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.FaceComparisonProvenance
import kotlinx.coroutines.CancellationException

/**
 * Local visual-profile consistency service.
 *
 * Preferred path, after explicit installation consent and a per-scan choice:
 * YuNet five-landmark detection -> SFace alignCrop -> SFace cosine correlation.
 *
 * A user-imported calibrated ONNX/TFLite embedding model remains supported for
 * research. The dependency-free appearance descriptor remains the final
 * fallback for reused or near-identical profile photos. No path uploads images,
 * crops, landmarks, or embeddings.
 */
class FaceEmbeddingService(context: Context) {
    private val appContext = context.applicationContext
    private val faceEmbedder = FaceEmbedder(appContext)
    private val modelStore = FaceEmbeddingModelStore(appContext).also {
        it.ensureModelAvailable()
    }
    private val calibrationStore = FaceEmbeddingCalibrationStore(appContext)
    private val correlationPack = FaceCorrelationModelPack(appContext)
    private val correlationConsent = FaceCorrelationConsentStore(appContext)
    private val correlationCalibration = FaceCorrelationCalibrationStore(appContext)
    private val correlationEngine by lazy {
        YuNetSFaceCorrelationEngine(
            context = appContext,
            modelPack = correlationPack,
            calibrationStore = correlationCalibration
        )
    }

    suspend fun extractAndCompare(
        selfieUri: Uri,
        profileUri: Uri
    ): FaceConsistencyMatch =
        compareFaces(
            selfieUri = selfieUri,
            profileUri = profileUri,
            profileUrl = profileUri.toString()
        )

    suspend fun compareFaces(
        selfieUri: Uri,
        profileUri: Uri,
        profileUrl: String
    ): FaceConsistencyMatch {
        val strongAllowed =
            FaceCorrelationSessionPolicy.isStrongCorrelationEnabled() &&
                correlationConsent.hasConsent() &&
                correlationPack.isReady()
        if (strongAllowed) {
            try {
                check(correlationPack.verifyForInference()) {
                    "Pinned YuNet/SFace files failed SHA-256 verification before inference."
                }
                return correlationEngine.compare(selfieUri, profileUri, profileUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A native/model failure must not silently disable the entire
                // scan. Fall back, but disclose the failed strong pipeline.
                val fallback = compareWithLegacyBackends(
                    selfieUri,
                    profileUri,
                    profileUrl
                )
                return fallback.copy(
                    warning = "${fallback.warning} Verified YuNet/SFace correlation could not run: " +
                        (error.localizedMessage ?: error.javaClass.simpleName)
                )
            }
        }
        return compareWithLegacyBackends(selfieUri, profileUri, profileUrl)
    }

    fun isCalibratedReviewScore(score: Float): Boolean {
        if (
            FaceCorrelationSessionPolicy.isStrongCorrelationEnabled() &&
            correlationConsent.hasConsent() &&
            correlationPack.isReady()
        ) {
            val thresholds = correlationCalibration.getThresholds()
            return thresholds.measured &&
                thresholds.decision(score) != FaceCorrelationDecision.NO_SUPPORT
        }
        return calibrationStore.getThresholds()?.isReviewScore(score) == true
    }

    fun strongCorrelationStatus(): String = when {
        !correlationPack.isReady() ->
            "YuNet/SFace model pack is not installed."
        !correlationConsent.hasConsent() ->
            "YuNet/SFace is installed but installation consent is not active."
        !FaceCorrelationSessionPolicy.isStrongCorrelationEnabled() ->
            "YuNet/SFace is installed but basic matching was selected for this scan."
        correlationCalibration.hasMeasuredCalibration() ->
            "YuNet/SFace is active with a measured, hash-bound calibration."
        else ->
            "YuNet/SFace is active with the reference threshold policy; results remain manual-review evidence."
    }

    private suspend fun compareWithLegacyBackends(
        selfieUri: Uri,
        profileUri: Uri,
        profileUrl: String
    ): FaceConsistencyMatch {
        val selfieFace = faceEmbedder.extractFaceBitmap(selfieUri)
        if (selfieFace == null) {
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "No face detected in selected selfie; visual consistency was not scored.",
                provenance = FaceComparisonProvenance(
                    backend = FaceComparisonBackend.NotRun,
                    calibration = FaceComparisonCalibrationState.NotApplicable
                )
            )
        }

        val profileFace = faceEmbedder.extractFaceBitmap(profileUri)
        if (profileFace == null) {
            selfieFace.recycle()
            return FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0f,
                warning = "No face detected in profile image; visual consistency was not scored.",
                provenance = FaceComparisonProvenance(
                    backend = FaceComparisonBackend.NotRun,
                    calibration = FaceComparisonCalibrationState.NotApplicable
                )
            )
        }

        return try {
            if (modelStore.isModelImported()) {
                runModelComparison(selfieFace, profileFace, profileUrl)
            } else {
                runAppearanceFallback(selfieFace, profileFace, profileUrl)
            }
        } finally {
            selfieFace.recycle()
            profileFace.recycle()
        }
    }

    private fun runModelComparison(
        selfieFace: android.graphics.Bitmap,
        profileFace: android.graphics.Bitmap,
        profileUrl: String
    ): FaceConsistencyMatch = runCatching {
        val runner = FaceEmbeddingModelRunner(modelStore.getModelFile())
        val selfieEmbedding = runner.embed(selfieFace)
        val profileEmbedding = runner.embed(profileFace)
        val score = FaceEmbeddingModelRunner.cosineSimilarity(
            selfieEmbedding,
            profileEmbedding
        )
        val thresholds = calibrationStore.getThresholds()
        FaceConsistencyMatch(
            profileUrl = profileUrl,
            similarityScore = score,
            warning = warningForModelScore(score, thresholds),
            provenance = embeddingProvenance(thresholds)
        )
    }.getOrElse { error ->
        val fallback = runAppearanceFallback(
            selfieFace,
            profileFace,
            profileUrl
        )
        fallback.copy(
            warning = "${fallback.warning} The configured embedding model failed: " +
                (error.localizedMessage ?: error.javaClass.simpleName)
        )
    }

    private fun runAppearanceFallback(
        selfieFace: android.graphics.Bitmap,
        profileFace: android.graphics.Bitmap,
        profileUrl: String
    ): FaceConsistencyMatch {
        val selfieDescriptor = FaceAppearanceDescriptor.describe(selfieFace)
        val profileDescriptor = FaceAppearanceDescriptor.describe(profileFace)
        val raw = FaceAppearanceDescriptor.cosineSimilarity(
            selfieDescriptor,
            profileDescriptor
        )
        // Appearance descriptors tend to cluster high; remap the useful region
        // conservatively and keep thresholds strict to avoid identity claims.
        val score = ((raw - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val warning = when {
            score >= 0.90f ->
                "Built-in local appearance descriptor reports high visual similarity for the face crops. " +
                    "This usually indicates the same or a near-duplicate photo; it is not cross-photo biometric identity proof."
            score >= 0.74f ->
                "Built-in local appearance descriptor reports a review-range visual similarity. " +
                    "Treat this only as supporting evidence for possible photo reuse."
            else ->
                "Built-in local appearance descriptor reports low visual similarity. " +
                    "This fallback does not identify people across unrelated photographs."
        }
        return FaceConsistencyMatch(
            profileUrl = profileUrl,
            similarityScore = score,
            warning = warning,
            provenance = FaceComparisonProvenance(
                backend = FaceComparisonBackend.AppearanceDescriptor,
                calibration = FaceComparisonCalibrationState.NotApplicable,
                modelSource = "Built-in local appearance descriptor"
            )
        )
    }

    private fun embeddingProvenance(
        thresholds: FaceEmbeddingThresholds?
    ): FaceComparisonProvenance {
        val calibration = when {
            thresholds == null -> FaceComparisonCalibrationState.Unavailable
            calibrationStore.isUsingBundledCalibration() ->
                FaceComparisonCalibrationState.ReferencePolicy
            else -> FaceComparisonCalibrationState.ImportedArtifact
        }
        return FaceComparisonProvenance(
            backend = FaceComparisonBackend.ImportedEmbeddingModel,
            calibration = calibration,
            modelSource = modelStore.modelSourceLabel(),
            modelHashes = modelStore.importedModelSha256()?.let(::listOf).orEmpty()
        )
    }

    private fun warningForModelScore(
        score: Float,
        thresholds: FaceEmbeddingThresholds?
    ): String = when {
        thresholds == null ->
            "Face embedding model produced a cosine score, but no matching calibration thresholds are available."
        thresholds.isSamePersonScore(score) ->
            "Calibrated face model reports a high visual similarity score. Confirm account ownership manually."
        thresholds.isReviewScore(score) ->
            "Calibrated face model reports a review-range similarity score. Treat as supporting evidence only."
        else ->
            "Calibrated face model reports a low similarity score."
    }
}
