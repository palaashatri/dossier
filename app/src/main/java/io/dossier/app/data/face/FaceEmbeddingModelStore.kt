package io.dossier.app.data.face

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Face embedding model storage.
 *
 * Priority:
 * 1. User-imported ONNX/TFLite in app filesDir (optional override)
 * 2. Bundled FaceNet TFLite shipped in assets (`models/facenet.tflite`)
 *
 * Calibrated thresholds live in [FaceEmbeddingCalibrationStore]. Replacing the
 * model clears user calibration so a mismatched sidecar is never applied.
 */
class FaceEmbeddingModelStore(private val context: Context) {

    /**
     * Ensures a usable model file exists (copies the bundled asset on first use).
     * Safe to call from IO threads; no-op when a valid model is already present.
     */
    fun ensureModelAvailable(): Boolean {
        if (isModelReady()) return true
        return runCatching { installBundledModel() }.isSuccess && isModelReady()
    }

    fun getModelFile(): File {
        ensureModelAvailable()
        return modelFiles().firstOrNull { it.exists() && it.length() >= MIN_MODEL_BYTES }
            ?: File(context.filesDir, TFLITE_MODEL_FILE_NAME)
    }

    fun isModelImported(): Boolean = isModelReady()

    fun isUsingBundledModel(): Boolean {
        if (!isModelReady()) return false
        val marker = File(context.filesDir, BUNDLED_MARKER_FILE)
        if (!marker.exists()) return false
        val activeSha = importedModelSha256() ?: return false
        return marker.readText().trim().equals(activeSha, ignoreCase = true)
    }

    fun isUserOverride(): Boolean = isModelReady() && !isUsingBundledModel()

    fun importedModelSizeBytes(): Long =
        getModelFile().takeIf { it.exists() }?.length() ?: 0L

    fun importedModelSha256(): String? =
        getModelFile()
            .takeIf { it.exists() && it.length() >= MIN_MODEL_BYTES }
            ?.sha256()

    fun modelSourceLabel(): String = when {
        !isModelReady() -> "None"
        isUsingBundledModel() -> "Bundled FaceNet"
        else -> "User import"
    }

    fun importModel(uri: Uri) {
        val displayName = displayNameFor(uri)
        if (displayName != null && !acceptsFileName(displayName)) {
            error("Select an ONNX or TFLite face embedding model.")
        }

        val extension = displayName?.substringAfterLast('.', missingDelimiterValue = "tflite")?.lowercase()
            ?: "tflite"
        val target = File(
            context.filesDir,
            if (extension == "onnx") ONNX_MODEL_FILE_NAME else TFLITE_MODEL_FILE_NAME
        )
        val tempFile = File(target.parentFile, "${target.name}.tmp")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to read selected model file.")

            if (tempFile.length() < MIN_MODEL_BYTES) {
                error("Selected face model is too small (${tempFile.length()} bytes).")
            }

            if (!tempFile.renameTo(target)) {
                error("Unable to store selected face model.")
            }
            modelFiles()
                .filter { it != target && it.exists() }
                .forEach { it.delete() }
            // User override — clear bundled marker and any prior calibration.
            File(context.filesDir, BUNDLED_MARKER_FILE).delete()
            FaceEmbeddingCalibrationStore(context).clearCalibration()
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    /**
     * Restore the shipped FaceNet model (clears a user override).
     */
    fun restoreBundledModel() {
        modelFiles().forEach { if (it.exists()) it.delete() }
        File(context.filesDir, BUNDLED_MARKER_FILE).delete()
        FaceEmbeddingCalibrationStore(context).clearCalibration()
        installBundledModel()
    }

    private fun installBundledModel() {
        val target = File(context.filesDir, TFLITE_MODEL_FILE_NAME)
        val tempFile = File(context.filesDir, "${TFLITE_MODEL_FILE_NAME}.tmp")
        try {
            context.assets.open(BUNDLED_ASSET_PATH).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.length() < MIN_MODEL_BYTES) {
                error("Bundled face model is missing or too small.")
            }
            modelFiles().filter { it != target && it.exists() }.forEach { it.delete() }
            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) {
                error("Unable to install bundled face model.")
            }
            val sha = target.sha256()
            File(context.filesDir, BUNDLED_MARKER_FILE).writeText(sha)
            // Install matching factory calibration for the bundled model.
            FaceEmbeddingCalibrationStore(context).ensureBundledCalibration()
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun isModelReady(): Boolean =
        modelFiles().any { it.exists() && it.length() >= MIN_MODEL_BYTES }

    private fun displayNameFor(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()

    companion object {
        const val TFLITE_MODEL_FILE_NAME = "face-embedding-model.tflite"
        const val ONNX_MODEL_FILE_NAME = "face-embedding-model.onnx"
        const val MIN_MODEL_BYTES = 4096L
        const val BUNDLED_ASSET_PATH = "models/facenet.tflite"
        const val BUNDLED_MARKER_FILE = "face-embedding-model.bundled"
        /** Documented source of the shipped weights (FaceNet TFLite). */
        const val BUNDLED_MODEL_ATTRIBUTION =
            "Bundled FaceNet TFLite (open Android FaceNet weights used for on-device embeddings)."

        fun acceptsFileName(fileName: String): Boolean {
            val normalized = fileName.lowercase()
            return normalized.endsWith(".tflite") || normalized.endsWith(".onnx")
        }
    }

    private fun modelFiles(): List<File> =
        listOf(
            File(context.filesDir, ONNX_MODEL_FILE_NAME),
            File(context.filesDir, TFLITE_MODEL_FILE_NAME)
        )
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
