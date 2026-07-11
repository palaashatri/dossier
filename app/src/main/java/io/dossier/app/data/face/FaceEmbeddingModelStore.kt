package io.dossier.app.data.face

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Stores a user-supplied face embedding model for the ONNX/TFLite runtime.
 * Calibrated score thresholds live in FaceEmbeddingCalibrationStore and are
 * cleared whenever the model is replaced.
 */
class FaceEmbeddingModelStore(private val context: Context) {

    fun getModelFile(): File =
        modelFiles().firstOrNull { it.exists() } ?: File(context.filesDir, TFLITE_MODEL_FILE_NAME)

    fun isModelImported(): Boolean =
        getModelFile().let { it.exists() && it.length() >= MIN_MODEL_BYTES }

    fun importedModelSizeBytes(): Long =
        getModelFile().takeIf { it.exists() }?.length() ?: 0L

    fun importedModelSha256(): String? =
        getModelFile()
            .takeIf { it.exists() && it.length() >= MIN_MODEL_BYTES }
            ?.sha256()

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
            FaceEmbeddingCalibrationStore(context).clearCalibration()
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

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
