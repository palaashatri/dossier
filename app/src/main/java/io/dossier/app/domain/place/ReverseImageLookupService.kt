package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.data.place.ExifParser
import io.dossier.app.data.place.FaceAnalyzer
import io.dossier.app.data.place.ImageLabeler
import io.dossier.app.data.place.TextRecognizer
import io.dossier.app.data.web.WebLocationSearcher
import io.dossier.app.domain.model.ReverseImageLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Orchestrates location intelligence and whole-image near-duplicate discovery.
 *
 * Query image bytes remain on-device. Dossier searches only text/identity clues,
 * downloads public candidate images, and compares them locally. Faces never enable
 * facial identification; whole-image duplicate matching may still detect the same
 * uploaded image after resizing, recompression, screenshots, or modest crops.
 */
class ReverseImageLookupService(private val context: Context) {

    suspend fun lookup(uri: Uri, deepResearch: Boolean = false): ReverseImageLookupResult =
        withContext(Dispatchers.IO) {
            val faceAnalyzer = FaceAnalyzer(context)
            val exifParser = ExifParser(context)
            val textRecognizer = TextRecognizer(context)
            val imageLabeler = ImageLabeler(context)

            val faceResult = faceAnalyzer.analyze(uri)
            val faceDetected = faceResult.faceDetected
            val gps = exifParser.parseGps(uri)
            val extractedText = textRecognizer.recognize(uri)
            val labels = imageLabeler.label(uri)

            coroutineScope {
                val visualDeferred = async(Dispatchers.IO) {
                    runCatching {
                        ReverseImageVisualMatcher(context).match(
                            queryUri = uri,
                            extractedText = extractedText,
                            labels = labels.map { it.text },
                            deepResearch = deepResearch
                        )
                    }.getOrElse { error ->
                        ReverseImageVisualMatcher.Outcome(
                            matches = emptyList(),
                            note = "Visual duplicate matching was unavailable: ${
                                error.localizedMessage ?: error.javaClass.simpleName
                            }",
                            candidateCount = 0
                        )
                    }
                }

                val webDeferred = if (gps == null) {
                    async(Dispatchers.IO) {
                        runCatching {
                            WebLocationSearcher(context).search(
                                extractedText,
                                labels.map { it.text },
                                deepResearch = deepResearch
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }

                val visual = visualDeferred.await()
                val webResult = webDeferred?.await()
                val resolvedLocation = gps ?: webResult?.resolvedLocation
                val mapsUrl = when {
                    gps != null -> "https://www.google.com/maps/search/?api=1&query=$gps"
                    webResult?.mapsUrl != null -> webResult.mapsUrl
                    resolvedLocation != null ->
                        "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(resolvedLocation)}"
                    else -> null
                }

                ReverseImageLookupResult(
                    gps = gps,
                    extractedText = extractedText,
                    labels = labels,
                    faceDetected = faceDetected,
                    faceWarning = if (faceDetected) FACE_WARNING else null,
                    resolvedLocation = resolvedLocation,
                    mapsUrl = mapsUrl,
                    webEvidence = webResult?.evidence.orEmpty(),
                    visualMatches = visual.matches,
                    visualCandidates = visual.candidates,
                    visualClusters = visual.clusters,
                    visualSearchNote = visual.note
                )
            }
        }

    private companion object {
        const val FACE_WARNING =
            "Face detected — facial identification remains disabled. Whole-image duplicate matching may continue because it compares the complete image, not identity across different photos."
    }
}
