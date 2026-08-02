package io.dossier.app.data.face

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the official OpenCV Zoo YuNet + SFace model pair used by Dossier's
 * strong cross-photo face-correlation pipeline.
 *
 * The models are fetched only after an explicit user action. Each file is
 * content-pinned by its Git LFS SHA-256 OID and exact byte length, written to a
 * temporary file, fsynced, verified, and atomically promoted. A compromised or
 * changed download is rejected before OpenCV can load it.
 */
class FaceCorrelationModelPack(
    context: Context,
    private val client: OkHttpClient = defaultClient()
) {
    data class ModelSpec(
        val id: String,
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val license: String
    )

    data class Status(
        val ready: Boolean,
        val installedBytes: Long,
        val expectedBytes: Long,
        val pipelineVersion: String,
        val detail: String
    )

    private val directory = File(context.filesDir, MODEL_DIRECTORY)

    @Volatile
    private var verifiedReady: Boolean = false

    fun isReady(): Boolean {
        if (verifiedReady) return true
        val ready = MODEL_SPECS.all(::verifyInstalledModel)
        verifiedReady = ready
        return ready
    }

    fun status(): Status {
        val installedBytes = MODEL_SPECS.sumOf { spec -> modelFile(spec).takeIf(File::exists)?.length() ?: 0L }
        val ready = isReady()
        return Status(
            ready = ready,
            installedBytes = installedBytes,
            expectedBytes = EXPECTED_PACK_BYTES,
            pipelineVersion = PIPELINE_VERSION,
            detail = if (ready) {
                "YuNet + SFace verified and ready for on-device correlation."
            } else {
                "Strong face correlation is not installed; Dossier will use the conservative appearance fallback."
            }
        )
    }

    suspend fun install(onProgress: suspend (Float) -> Unit = {}): Status = withContext(Dispatchers.IO) {
        if (isReady()) {
            onProgress(1f)
            return@withContext status()
        }

        directory.mkdirs()
        require(directory.isDirectory) { "Unable to create face-correlation model directory." }

        var completedBytes = 0L
        try {
            for (spec in MODEL_SPECS) {
                currentCoroutineContext().ensureActive()
                val target = modelFile(spec)
                if (verifyInstalledModel(spec)) {
                    completedBytes += spec.sizeBytes
                    onProgress(completedBytes.toFloat() / EXPECTED_PACK_BYTES.toFloat())
                    continue
                }
                downloadAndVerify(spec, target, completedBytes, onProgress)
                completedBytes += spec.sizeBytes
            }
            writeVerifiedMarker()
            verifiedReady = MODEL_SPECS.all(::verifyInstalledModel)
            check(verifiedReady) { "Downloaded face-correlation models failed final verification." }
            onProgress(1f)
            status()
        } catch (cancelled: CancellationException) {
            cleanupTemporaryFiles()
            throw cancelled
        } catch (error: Exception) {
            cleanupTemporaryFiles()
            verifiedReady = false
            throw error
        }
    }

    fun delete() {
        verifiedReady = false
        if (directory.exists()) directory.deleteRecursively()
    }

    fun yunetModelFile(): File = requireModel(YUNET)

    fun sfaceModelFile(): File = requireModel(SFACE)

    private fun requireModel(spec: ModelSpec): File {
        check(isReady()) { "YuNet/SFace model pack is not installed or failed integrity verification." }
        return modelFile(spec)
    }

    private suspend fun downloadAndVerify(
        spec: ModelSpec,
        target: File,
        completedBytes: Long,
        onProgress: suspend (Float) -> Unit
    ) {
        val temp = File(directory, "${spec.fileName}.download")
        if (temp.exists()) temp.delete()
        val request = Request.Builder()
            .url(spec.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "${spec.id} download failed with HTTP ${response.code}."
                }
                val body = response.body ?: error("${spec.id} download returned an empty body.")
                val advertisedLength = body.contentLength()
                if (advertisedLength >= 0L) {
                    check(advertisedLength == spec.sizeBytes) {
                        "${spec.id} download length changed: expected ${spec.sizeBytes}, got $advertisedLength."
                    }
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var lastProgressBytes = 0L
                FileOutputStream(temp).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            written += read
                            check(written <= spec.sizeBytes) {
                                "${spec.id} download exceeded the pinned size."
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (written - lastProgressBytes >= PROGRESS_STEP_BYTES) {
                                lastProgressBytes = written
                                onProgress(
                                    ((completedBytes + written).toDouble() / EXPECTED_PACK_BYTES.toDouble())
                                        .toFloat()
                                        .coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }

                check(written == spec.sizeBytes) {
                    "${spec.id} download was truncated: expected ${spec.sizeBytes}, got $written."
                }
                val actualSha = digest.digest().toHex()
                check(actualSha.equals(spec.sha256, ignoreCase = true)) {
                    "${spec.id} checksum mismatch; the downloaded file was discarded."
                }
            }

            if (target.exists() && !target.delete()) {
                error("Unable to replace existing ${spec.id} model.")
            }
            if (!temp.renameTo(target)) {
                error("Unable to install verified ${spec.id} model.")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun verifyInstalledModel(spec: ModelSpec): Boolean {
        val file = modelFile(spec)
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        return runCatching { file.sha256().equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)
    }

    private fun modelFile(spec: ModelSpec): File = File(directory, spec.fileName)

    private fun writeVerifiedMarker() {
        val marker = File(directory, VERIFIED_MARKER_FILE)
        val temp = File(directory, "$VERIFIED_MARKER_FILE.tmp")
        val content = buildString {
            appendLine(PIPELINE_VERSION)
            MODEL_SPECS.forEach { appendLine("${it.id}:${it.sha256}:${it.sizeBytes}") }
        }
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (marker.exists()) marker.delete()
        check(temp.renameTo(marker)) { "Unable to store face-model verification marker." }
    }

    private fun cleanupTemporaryFiles() {
        directory.listFiles()
            ?.filter { it.name.endsWith(".download") || it.name.endsWith(".tmp") }
            ?.forEach(File::delete)
    }

    companion object {
        const val PIPELINE_VERSION = "yunet-2023mar+sface-2021dec-aligncrop-v1"
        const val MODEL_DIRECTORY = "face-correlation-v1"
        const val VERIFIED_MARKER_FILE = "verified-models.txt"

        // OpenCV Zoo files are stored through Git LFS. These hashes are their
        // published LFS object IDs and therefore hash the actual model bytes.
        val YUNET = ModelSpec(
            id = "YuNet 2023mar",
            fileName = "face_detection_yunet_2023mar.onnx",
            url = "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
            sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
            sizeBytes = 232_589L,
            license = "MIT"
        )

        val SFACE = ModelSpec(
            id = "SFace 2021dec",
            fileName = "face_recognition_sface_2021dec.onnx",
            url = "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx",
            sha256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
            sizeBytes = 38_696_353L,
            license = "Apache-2.0"
        )

        val MODEL_SPECS: List<ModelSpec> = listOf(YUNET, SFACE)
        val EXPECTED_PACK_BYTES: Long = MODEL_SPECS.sumOf(ModelSpec::sizeBytes)

        private const val USER_AGENT =
            "Dossier/0.1 authorized-on-device-face-correlation (+https://github.com/palaashatri/dossier)"
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
