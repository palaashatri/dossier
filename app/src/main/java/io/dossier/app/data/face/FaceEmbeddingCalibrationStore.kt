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

    fun getThresholds(): FaceEmbeddingThresholds? =
        if (calibrationFile.exists()) {
            runCatching { parseCalibrationJson(calibrationFile.readText()) }
                .mapCatching { thresholds ->
                    thresholds.takeIf { it.modelSha256.equals(modelStore.importedModelSha256(), ignoreCase = true) }
                }
                .getOrNull()
        } else {
            null
        }

    fun isCalibrationImported(): Boolean =
        getThresholds() != null

    fun importCalibration(uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { it.readText() }
        } ?: error("Unable to read selected calibration file.")

        val thresholds = parseCalibrationJson(json)
        val currentModelSha256 = modelStore.importedModelSha256()
            ?: error("Import a face embedding model before importing calibration.")
        require(thresholds.modelSha256.equals(currentModelSha256, ignoreCase = true)) {
            "Calibration modelSha256 does not match the imported face model."
        }

        val tempFile = File(context.filesDir, "$CALIBRATION_FILE_NAME.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
            if (!tempFile.renameTo(calibrationFile)) {
                error("Unable to store selected calibration file.")
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    fun clearCalibration() {
        if (calibrationFile.exists()) calibrationFile.delete()
    }

    companion object {
        private const val CALIBRATION_FILE_NAME = "face-embedding-calibration.json"

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
