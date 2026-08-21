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
 * Query image bytes remain on-device. If the selected image already contains EXIF
 * coordinates/time, Dossier may send only those coordinates/date to public mapping
 * and weather endpoints for corroboration. Faces never enable facial identification;
 * whole-image duplicate matching only compares the complete selected image.
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
            val metadata = exifParser.parseMetadata(uri)
            val gps = metadata?.gps
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

                val geoDeferred = if (metadata?.latitude != null && metadata.longitude != null) {
                    async(Dispatchers.IO) {
                        runCatching { GeoCorroborationService().corroborate(metadata) }.getOrNull()
                    }
                } else null

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
                } else null

                val visual = visualDeferred.await()
                val geo = geoDeferred?.await()
                val webResult = webDeferred?.await()
                val resolvedLocation = geo?.displayName ?: gps ?: webResult?.resolvedLocation
                val mapsUrl = when {
                    gps != null -> "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(gps)}"
                    webResult?.mapsUrl != null -> webResult.mapsUrl
                    resolvedLocation != null ->
                        "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(resolvedLocation)}"
                    else -> null
                }
                val combinedEvidence = (geo?.evidence.orEmpty() + webResult?.evidence.orEmpty())
                    .distinctBy { "${it.title}|${it.url}" }
                    .take(MAX_LOCATION_EVIDENCE)

                val result = ReverseImageLookupResult(
                    gps = gps,
                    extractedText = extractedText,
                    labels = labels,
                    faceDetected = faceDetected,
                    faceWarning = if (faceDetected) FACE_WARNING else null,
                    resolvedLocation = resolvedLocation,
                    mapsUrl = mapsUrl,
                    webEvidence = combinedEvidence,
                    visualMatches = visual.matches,
                    visualCandidates = visual.candidates,
                    visualClusters = visual.clusters,
                    visualSearchNote = visual.note
                )
                MediaIntelligenceSession.recordImage(result)
                result
            }
        }

    private companion object {
        const val MAX_LOCATION_EVIDENCE = 10
        const val FACE_WARNING =
            "Face detected — facial identification remains disabled. Whole-image duplicate matching may continue because it compares the complete image, not identity across different photos."
    }
}
