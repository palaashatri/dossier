package io.dossier.app.data.face

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream

class FaceEmbeddingCalibrationStore(private val context: Context) {
    private val calibrationFile = File(context.filesDir, CALIBRATION_FILE_NAME)
    private val modelStore = FaceEmbeddingModelStore(context)

    fun getThresholds(): FaceEmbeddingThresholds? {
        // Prefer user-imported calibration when it matches the active model.
        if (calibrationFile.exists()) {
            val imported = runCatching { parseCalibrationJson(calibrationFile.readText()) }
                .mapCatching { thresholds ->
                    thresholds.takeIf {
                        it.modelSha256.equals(modelStore.importedModelSha256(), ignoreCase = true)
                    }
                }
                .getOrNull()
            if (imported != null) return imported
        }
        // Fall back to bundled factory defaults when using the shipped FaceNet.
        return loadBundledThresholds()
    }

    fun isCalibrationImported(): Boolean =
        getThresholds() != null

    fun isUsingBundledCalibration(): Boolean {
        val thresholds = getThresholds() ?: return false
        return thresholds.source.contains("bundled", ignoreCase = true) ||
            thresholds.source.contains("factory", ignoreCase = true)
    }

    fun importCalibration(uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { it.readText() }
        } ?: error("Unable to read selected calibration file.")

        val thresholds = parseCalibrationJson(json)
        modelStore.ensureModelAvailable()
        val currentModelSha256 = modelStore.importedModelSha256()
            ?: error("Face embedding model is not available.")
        require(thresholds.modelSha256.equals(currentModelSha256, ignoreCase = true)) {
            "Calibration modelSha256 does not match the active face model."
        }

        writeCalibrationJson(json)
    }

    /**
     * Installs the asset-shipped calibration JSON for the bundled FaceNet when
     * the active model SHA matches. No-op for user overrides with different SHA.
     */
    fun ensureBundledCalibration() {
        val bundled = loadBundledThresholdsFromAssets() ?: return
        val activeSha = modelStore.importedModelSha256() ?: return
        if (!bundled.modelSha256.equals(activeSha, ignoreCase = true)) return
        // Only overwrite if missing or still the previous bundled file.
        if (calibrationFile.exists()) {
            val existing = runCatching { parseCalibrationJson(calibrationFile.readText()) }.getOrNull()
            if (existing != null &&
                !existing.source.contains("bundled", ignoreCase = true) &&
                !existing.source.contains("factory", ignoreCase = true)
            ) {
                // Keep a real user-provided evaluation calibration.
                if (existing.modelSha256.equals(activeSha, ignoreCase = true)) return
            }
        }
        writeCalibrationJson(bundled.toCalibrationJson())
    }

    fun clearCalibration() {
        if (calibrationFile.exists()) calibrationFile.delete()
    }

    private fun writeCalibrationJson(json: String) {
        val tempFile = File(context.filesDir, "$CALIBRATION_FILE_NAME.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
            if (!tempFile.renameTo(calibrationFile)) {
                error("Unable to store calibration file.")
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun loadBundledThresholds(): FaceEmbeddingThresholds? {
        val activeSha = modelStore.importedModelSha256() ?: return null
        val bundled = loadBundledThresholdsFromAssets() ?: return null
        return bundled.takeIf { it.modelSha256.equals(activeSha, ignoreCase = true) }
    }

    private fun loadBundledThresholdsFromAssets(): FaceEmbeddingThresholds? =
        runCatching {
            context.assets.open(BUNDLED_CALIBRATION_ASSET).bufferedReader().use { it.readText() }
                .let { parseCalibrationJson(it) }
        }.getOrNull()

    companion object {
        private const val CALIBRATION_FILE_NAME = "face-embedding-calibration.json"
        const val BUNDLED_CALIBRATION_ASSET = "models/facenet-calibration.json"

        fun parseCalibrationJson(json: String): FaceEmbeddingThresholds {
            val root = Json.parseToJsonElement(json).jsonObject
            val reviewThreshold = root.floatValue("reviewThreshold")
                ?: root.floatValue("review_threshold")
                ?: error("Calibration file is missing reviewThreshold.")
            val samePersonThreshold = root.floatValue("samePersonThreshold")
                ?: root.floatValue("same_person_threshold")
                ?: error("Calibration file is missing samePersonThreshold.")
            val source = root.stringValue("source")
                ?: root.stringValue("dataset")
                ?: "Imported calibration"
            val modelSha256 = root.stringValue("modelSha256")
                ?: root.stringValue("model_sha256")
                ?: error("Calibration file is missing modelSha256.")
            return FaceEmbeddingThresholds(
                reviewThreshold = reviewThreshold,
                samePersonThreshold = samePersonThreshold,
                source = source,
                modelSha256 = modelSha256.lowercase(),
                positivePairCount = root.intValue("positivePairCount")
                    ?: root.intValue("positive_pair_count")
                    ?: error("Calibration file is missing positivePairCount."),
                negativePairCount = root.intValue("negativePairCount")
                    ?: root.intValue("negative_pair_count")
                    ?: error("Calibration file is missing negativePairCount."),
                reviewFalseAcceptRate = root.floatValue("reviewFalseAcceptRate")
                    ?: root.floatValue("review_false_accept_rate")
                    ?: error("Calibration file is missing reviewFalseAcceptRate."),
                samePersonFalseAcceptRate = root.floatValue("samePersonFalseAcceptRate")
                    ?: root.floatValue("same_person_false_accept_rate")
                    ?: error("Calibration file is missing samePersonFalseAcceptRate."),
                reviewTrueAcceptRate = root.floatValue("reviewTrueAcceptRate")
                    ?: root.floatValue("review_true_accept_rate")
                    ?: error("Calibration file is missing reviewTrueAcceptRate."),
                samePersonTrueAcceptRate = root.floatValue("samePersonTrueAcceptRate")
                    ?: root.floatValue("same_person_true_accept_rate")
                    ?: error("Calibration file is missing samePersonTrueAcceptRate.")
            )
        }

        private fun JsonObject.intValue(name: String): Int? =
            this[name]?.jsonPrimitive?.intOrNull

        private fun JsonObject.floatValue(name: String): Float? =
            this[name]?.jsonPrimitive?.floatOrNull

        private fun JsonObject.stringValue(name: String): String? =
            this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
