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
import java.util.Locale

/**
 * Orchestrates location intelligence and whole-image near-duplicate discovery.
 *
 * Query image bytes remain on-device. If the selected image already contains EXIF
 * coordinates/time, Dossier may send only those coordinates/date to public mapping
 * and weather endpoints for corroboration. Faces never enable facial identification;
 * whole-image duplicate matching only compares the complete selected image.
 */
class ReverseImageLookupService(private val context: Context) {

    suspend fun lookup(
        uri: Uri,
        deepResearch: Boolean = false,
        bindingToken: String
    ): ReverseImageLookupResult =
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
                val locationCandidates = buildLocationCandidates(
                    gps = gps,
                    resolvedLocation = resolvedLocation,
                    geoEvidence = geo?.evidence.orEmpty(),
                    webEvidence = webResult?.evidence.orEmpty(),
                    labels = labels
                )

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
                    visualSearchNote = visual.note,
                    locationCandidates = locationCandidates
                )
                MediaIntelligenceSession.recordImage(bindingToken, result)
                result
            }
        }

    private fun buildLocationCandidates(
        gps: String?,
        resolvedLocation: String?,
        geoEvidence: List<ReverseImageLookupResult.WebEvidence>,
        webEvidence: List<ReverseImageLookupResult.WebEvidence>,
        labels: List<ReverseImageLookupResult.ImageLabel>
    ): List<ReverseImageLookupResult.LocationCandidate> = buildList {
        gps?.takeIf(String::isNotBlank)?.let { value ->
            add(
                ReverseImageLookupResult.LocationCandidate(
                    value = value,
                    evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
                    reason = "Embedded GPS metadata from the selected photo"
                )
            )
        }

        val geoSource = geoEvidence.firstOrNull { it.url.isNotBlank() }?.url
        resolvedLocation
            ?.takeIf(String::isNotBlank)
            ?.takeIf { value -> none { sameLocationValue(it.value, value) } }
            ?.let { value ->
                val corroborated = geoEvidence.isNotEmpty()
                val imageSource = webEvidence.firstOrNull { it.url.isNotBlank() }?.url
                add(
                    ReverseImageLookupResult.LocationCandidate(
                        value = value,
                        evidenceClass = if (corroborated) {
                            ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION
                        } else {
                            ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION
                        },
                        reason = if (corroborated) {
                            "Location candidate corroborated from EXIF coordinates"
                        } else {
                            "Location candidate derived from public image-search observations"
                        },
                        sourceUrls = listOfNotNull(geoSource ?: imageSource)
                    )
                )
            }

        labels
            .asSequence()
            .filter(::isLocationCue)
            .take(MAX_VISUAL_LOCATION_LABELS)
            .forEach { label ->
                add(
                    ReverseImageLookupResult.LocationCandidate(
                        value = label.text,
                        evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS,
                        reason = "On-device image label is a visual scene clue, not a location proof",
                        confidence = label.confidence
                    )
                )
            }
    }.take(MAX_LOCATION_CANDIDATES)

    private fun sameLocationValue(first: String, second: String): Boolean =
        first.trim().replace(Regex("\\s+"), " ").equals(
            second.trim().replace(Regex("\\s+"), " "),
            ignoreCase = true
        )

    /**
     * ML image labels are broad scene observations. Only labels that carry an
     * explicit place/landmark cue may enter the location-candidate list; a
     * generic `person`, `car`, or `cafe` label is not a location.
     */
    private fun isLocationCue(label: ReverseImageLookupResult.ImageLabel): Boolean {
        val normalized = label.text.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized in GENERIC_VISUAL_LABELS) return false
        return LOCATION_CUE_TERMS.any { term -> normalized.contains(term) }
    }

    private companion object {
        const val MAX_LOCATION_EVIDENCE = 10
        const val MAX_LOCATION_CANDIDATES = 64
        const val MAX_VISUAL_LOCATION_LABELS = 8
        val GENERIC_VISUAL_LABELS = setOf(
            "person", "man", "woman", "face", "people", "car", "vehicle", "truck",
            "cafe", "coffee", "restaurant", "food", "dog", "cat", "animal", "tree",
            "building", "house", "room", "furniture", "clothing", "sky", "water"
        )
        val LOCATION_CUE_TERMS = setOf(
            "landmark", "monument", "statue", "tower", "bridge", "museum", "church",
            "temple", "mosque", "cathedral", "palace", "castle", "station", "airport",
            "street sign", "road sign", "city", "town", "village", "square", "park",
            "beach", "mountain", "river", "lake", "waterfall", "historic district"
        )
        const val FACE_WARNING =
            "Face detected — facial identification remains disabled. Whole-image duplicate matching may continue because it compares the complete image, not identity across different photos."
    }
}
