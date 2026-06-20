package io.dossier.app.domain.place

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.dossier.app.data.place.FaceAnalyzer
import io.dossier.app.data.place.ImageLabeler
import io.dossier.app.data.place.TextRecognizer
import io.dossier.app.data.web.WebLocationSearcher
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reverse video lookup via local frame sampling.
 *
 * This intentionally mirrors ReverseImageLookupService's privacy boundary:
 * video frames are never uploaded. The app samples a few frames locally, runs
 * face detection only as a safety gate, extracts OCR/scene labels, and searches
 * the public web using those text clues.
 */
class ReverseVideoLookupService(private val context: Context) {

    suspend fun lookup(uri: Uri, deepResearch: Boolean = false): ReverseVideoLookupResult =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val faceAnalyzer = FaceAnalyzer(context)
            val textRecognizer = TextRecognizer(context)
            val imageLabeler = ImageLabeler(context)
            val webSearcher = WebLocationSearcher(context)

            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                val timestamps = sampleTimestamps(durationMs)
                val frameSummaries = timestamps.mapNotNull { timestampMs ->
                    val frame = extractFrame(retriever, timestampMs) ?: return@mapNotNull null
                    val faceDetected = faceAnalyzer.analyze(frame).faceDetected
                    val extractedText = textRecognizer.recognize(frame)
                    val labels = imageLabeler.label(frame)

                    ReverseVideoLookupResult.FrameEvidence(
                        timestampMs = timestampMs,
                        extractedText = extractedText,
                        labels = labels,
                        faceDetected = faceDetected
                    )
                }

                val extractedText = mergeFrameText(frameSummaries.mapNotNull { it.extractedText })
                val labels = mergeFrameLabels(frameSummaries.map { it.labels })
                val webResult = webSearcher.search(
                    textClues = extractedText,
                    labelClues = labels.map { it.text },
                    deepResearch = deepResearch
                )
                val resolvedLocation = webResult.resolvedLocation
                val mapsUrl = webResult.mapsUrl ?: resolvedLocation?.let {
                    "https://www.google.com/maps/search/?api=1&query=${Uri.encode(it)}"
                }
                val faceDetected = frameSummaries.any { it.faceDetected }

                ReverseVideoLookupResult(
                    durationMs = durationMs,
                    sampledFrames = frameSummaries.size,
                    extractedText = extractedText,
                    labels = labels,
                    faceDetected = faceDetected,
                    faceWarning = if (faceDetected) FACE_WARNING else null,
                    resolvedLocation = resolvedLocation,
                    mapsUrl = mapsUrl,
                    webEvidence = webResult.evidence,
                    frameSummaries = frameSummaries
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Some OEM retrievers throw on release after decode failures.
                }
            }
        }

    private fun extractFrame(
        retriever: MediaMetadataRetriever,
        timestampMs: Long
    ): Bitmap? {
        return try {
            val frame = retriever.getFrameAtTime(
                timestampMs * MICROSECONDS_PER_MILLISECOND,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            downscaleForMl(frame)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun downscaleForMl(bitmap: Bitmap): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        if (longestSide <= MAX_ML_FRAME_DIMENSION) return bitmap

        val scale = MAX_ML_FRAME_DIMENSION.toFloat() / longestSide.toFloat()
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    companion object {
        private const val FACE_WARNING =
            "Face detected in sampled frames - visual identity search disabled. Location lookup continues."
        private const val MAX_VIDEO_FRAMES = 5
        private const val MAX_ML_FRAME_DIMENSION = 1280
        private const val MICROSECONDS_PER_MILLISECOND = 1_000L

        fun sampleTimestamps(durationMs: Long?, maxFrames: Int = MAX_VIDEO_FRAMES): List<Long> {
            if (maxFrames <= 0) return emptyList()
            if (durationMs == null || durationMs <= 0L) return listOf(0L)
            if (durationMs <= 1_000L) return listOf(durationMs / 2L)

            val lastSafeMs = max(0L, durationMs - 1L)
            return List(maxFrames) { index ->
                val fraction = (index + 1).toDouble() / (maxFrames + 1).toDouble()
                (durationMs * fraction).roundToLong().coerceIn(0L, lastSafeMs)
            }.distinct()
        }

        fun mergeFrameText(texts: List<String>, maxChars: Int = 1_500): String? {
            val merged = texts
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .joinToString(separator = "\n\n")
                .take(maxChars)
                .trim()
            return merged.ifBlank { null }
        }

        fun mergeFrameLabels(
            frameLabels: List<List<ReverseImageLookupResult.ImageLabel>>,
            maxLabels: Int = 8
        ): List<ReverseImageLookupResult.ImageLabel> {
            if (maxLabels <= 0) return emptyList()
            val flattened = frameLabels.flatten()
            return flattened
                .groupBy { it.text.lowercase() }
                .map { (_, labels) ->
                    labels.maxBy { it.confidence }
                }
                .sortedByDescending { it.confidence }
                .take(min(maxLabels, flattened.size))
        }
    }
}
