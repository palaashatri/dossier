package io.dossier.app.domain.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object LocalAiModelDownloader {
    private val _downloadProgress = MutableStateFlow<Map<LocalAiModelType, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<LocalAiModelType, Float>> = _downloadProgress

    private val _isDownloading = MutableStateFlow<Map<LocalAiModelType, Boolean>>(emptyMap())
    val isDownloading: StateFlow<Map<LocalAiModelType, Boolean>> = _isDownloading

    // Real, human-readable error per model (e.g. "HTTP 401 (gated repository)").
    private val _downloadError = MutableStateFlow<Map<LocalAiModelType, String?>>(emptyMap())
    val downloadError: StateFlow<Map<LocalAiModelType, String?>> = _downloadError

    fun getModelFile(context: Context, modelType: LocalAiModelType): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, modelType.fileName)
    }

    /**
     * Honest availability. ML Kit Vision / AICore are not file-based and are
     * never "downloaded" here — their availability is checked by their engines.
     * For downloadable models, a file must exist AND be larger than the 1KB
     * threshold we historically used for dummy files, so a stale dummy can't
     * masquerade as a real model.
     */
    fun isModelDownloaded(context: Context, modelType: LocalAiModelType): Boolean {
        if (!modelType.downloadable) return false
        val file = getModelFile(context, modelType)
        // Reject the old 1KB dummy files that simulateMockDownload used to write.
        return file.exists() && file.length() > 4096
    }

    suspend fun downloadModel(context: Context, modelType: LocalAiModelType) = withContext(Dispatchers.IO) {
        clearError(modelType)

        if (!modelType.downloadable) {
            setError(modelType, "This engine is not downloadable in this build.")
            return@withContext
        }
        if (modelType.url.isBlank()) {
            setError(modelType, "No public download URL is available for this model.")
            return@withContext
        }
        if (isModelDownloaded(context, modelType)) return@withContext

        _isDownloading.value = _isDownloading.value.toMutableMap().apply { put(modelType, true) }
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply { put(modelType, 0f) }

        val file = getModelFile(context, modelType)
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.delete()

        try {
            val connection = (URL(modelType.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
                )
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                setError(
                    modelType,
                    "Download failed: HTTP $responseCode" +
                        if (responseCode == 401 || responseCode == 403) " (gated repository)" else ""
                )
                connection.disconnect()
                return@withContext
            }

            val fileLength = connection.contentLengthLong
            connection.getInputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val data = ByteArray(8192)
                    var total = 0L
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        output.write(data, 0, count)
                        if (fileLength > 0) {
                            val progress = total.toFloat() / fileLength
                            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                                put(modelType, progress)
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            // Sanity check: reject anything suspiciously small (would indicate an
            // error page or truncated payload rather than a real model).
            if (tempFile.length() <= 4096) {
                setError(modelType, "Downloaded file too small to be a valid model (${tempFile.length()} bytes).")
                tempFile.delete()
                return@withContext
            }

            tempFile.renameTo(file)
            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply { put(modelType, 1f) }
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
            setError(modelType, "Download failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            _isDownloading.value = _isDownloading.value.toMutableMap().apply { put(modelType, false) }
        }
    }

    fun deleteModel(context: Context, modelType: LocalAiModelType) {
        val file = getModelFile(context, modelType)
        if (file.exists()) file.delete()
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(modelType)
        }
        clearError(modelType)
    }

    private fun setError(modelType: LocalAiModelType, message: String) {
        _downloadError.value = _downloadError.value.toMutableMap().apply { put(modelType, message) }
    }

    private fun clearError(modelType: LocalAiModelType) {
        _downloadError.value = _downloadError.value.toMutableMap().apply { put(modelType, null) }
    }
}
