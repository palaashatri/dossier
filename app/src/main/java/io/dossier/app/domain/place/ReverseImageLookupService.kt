package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.data.place.ExifParser
import io.dossier.app.data.place.FaceAnalyzer
import io.dossier.app.data.place.ImageLabeler
import io.dossier.app.data.place.TextRecognizer
import io.dossier.app.data.web.WebLocationSearcher
import io.dossier.app.domain.model.ReverseImageLookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
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
        bindingToken: String,
        onProgress: suspend (ReverseImageLookupResult) -> Unit = {}
    ): ReverseImageLookupResult =
        withContext(Dispatchers.IO) {
            val faceAnalyzer = FaceAnalyzer(context)
            val exifParser = ExifParser(context)
            val textRecognizer = TextRecognizer(context)
            val imageLabeler = ImageLabeler(context)

            val local = analyzeLocally(
                uri = uri,
                faceAnalyzer = faceAnalyzer,
                exifParser = exifParser,
                textRecognizer = textRecognizer,
                imageLabeler = imageLabeler
            )
            val pendingVisual = pendingVisualOutcome()
            emitProgress(onProgress, buildResult(local, null, null, pendingVisual, local.notes))

            coroutineScope {
                val visualDeferred = async(Dispatchers.IO) {
                    boundedStage(
                        label = "Public candidate visual matching",
                        timeoutMs = VISUAL_STAGE_TIMEOUT_MS,
                        fallback = unavailableVisualOutcome(
                            "Public candidate visual matching timed out or was unavailable; local media evidence was retained."
                        )
                    ) {
                        ReverseImageVisualMatcher(context).match(
                            queryUri = uri,
                            extractedText = local.extractedText,
                            labels = local.labels.map { it.text },
                            deepResearch = deepResearch
                        )
                    }
                }

                val geoDeferred = if (local.metadata?.latitude != null && local.metadata.longitude != null) {
                    async(Dispatchers.IO) {
                        boundedStage(
                            label = "EXIF location corroboration",
                            timeoutMs = LOCATION_STAGE_TIMEOUT_MS,
                            fallback = null as GeoCorroborationService.Result?
                        ) {
                            GeoCorroborationService().corroborate(local.metadata!!)
                        }
                    }
                } else null

                val webDeferred = if (local.metadata?.gps == null) {
                    async(Dispatchers.IO) {
                        boundedStage(
                            label = "Public location search",
                            timeoutMs = LOCATION_STAGE_TIMEOUT_MS,
                            fallback = null as WebLocationSearcher.Result?
                        ) {
                            WebLocationSearcher(context).search(
                                local.extractedText,
                                local.labels.map { it.text },
                                deepResearch = deepResearch
                            )
                        }
                    }
                } else null

                val locationDeferred = async(Dispatchers.IO) {
                    val geoStage = geoDeferred?.await()
                    val webStage = webDeferred?.await()
                    val geo = geoStage?.value
                    val web = webStage?.value
                    val notes = listOfNotNull(geoStage?.note, webStage?.note)
                    if (geo != null || web != null || notes.isNotEmpty()) {
                        emitProgress(
                            onProgress,
                            buildResult(local, geo, web, pendingVisual, local.notes + notes)
                        )
                    }
                    LocationStage(geo = geo, web = web, notes = notes)
                }

                val visualStage = visualDeferred.await()
                val location = locationDeferred.await()
                val result = buildResult(
                    local = local,
                    geo = location.geo,
                    web = location.web,
                    visual = visualStage.value,
                    notes = local.notes + location.notes + listOfNotNull(visualStage.note)
                )
                MediaIntelligenceSession.recordImage(bindingToken, result)
                result
            }
        }

    private data class LocalAnalysis(
        val faceDetected: Boolean,
        val metadata: ExifParser.Metadata?,
        val extractedText: String?,
        val labels: List<ReverseImageLookupResult.ImageLabel>,
        val notes: List<String>
    )

    private data class StageResult<T>(val value: T, val note: String? = null)

    private data class LocationStage(
        val geo: GeoCorroborationService.Result?,
        val web: WebLocationSearcher.Result?,
        val notes: List<String>
    )

    private suspend fun analyzeLocally(
        uri: Uri,
        faceAnalyzer: FaceAnalyzer,
        exifParser: ExifParser,
        textRecognizer: TextRecognizer,
        imageLabeler: ImageLabeler
    ): LocalAnalysis = coroutineScope {
        val faceDeferred = async(Dispatchers.IO) {
            boundedStage(
                label = "On-device face detection",
                timeoutMs = LOCAL_STAGE_TIMEOUT_MS,
                fallback = false
            ) { faceAnalyzer.analyze(uri).faceDetected }
        }
        val metadataDeferred = async(Dispatchers.IO) {
            boundedStage(
                label = "Local EXIF extraction",
                timeoutMs = LOCAL_STAGE_TIMEOUT_MS,
                fallback = null as ExifParser.Metadata?
            ) { exifParser.parseMetadata(uri) }
        }
        val textDeferred = async(Dispatchers.IO) {
            boundedStage(
                label = "On-device OCR",
                timeoutMs = LOCAL_STAGE_TIMEOUT_MS,
                fallback = null as String?
            ) { textRecognizer.recognize(uri) }
        }
        val labelsDeferred = async(Dispatchers.IO) {
            boundedStage(
                label = "On-device image labeling",
                timeoutMs = LOCAL_STAGE_TIMEOUT_MS,
                fallback = emptyList<ReverseImageLookupResult.ImageLabel>()
            ) { imageLabeler.label(uri) }
        }

        val face = faceDeferred.await()
        val metadata = metadataDeferred.await()
        val text = textDeferred.await()
        val labels = labelsDeferred.await()
        LocalAnalysis(
            faceDetected = face.value,
            metadata = metadata.value,
            extractedText = text.value,
            labels = labels.value,
            notes = listOfNotNull(face.note, metadata.note, text.note, labels.note)
        )
    }

    private suspend fun <T> boundedStage(
        label: String,
        timeoutMs: Long,
        fallback: T,
        block: suspend () -> T
    ): StageResult<T> = try {
        StageResult(withTimeout(timeoutMs) { block() }, null)
    } catch (timeout: TimeoutCancellationException) {
        StageResult(
            fallback,
            "$label timed out after ${timeoutMs}ms; continuing with other evidence."
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        StageResult(
            fallback,
            "$label was unavailable; continuing with other evidence (${error.javaClass.simpleName})."
        )
    }

    private suspend fun emitProgress(
        onProgress: suspend (ReverseImageLookupResult) -> Unit,
        result: ReverseImageLookupResult
    ) {
        try {
            onProgress(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Progress observers are best-effort; the lookup result remains authoritative.
        }
    }

    private fun buildResult(
        local: LocalAnalysis,
        geo: GeoCorroborationService.Result?,
        web: WebLocationSearcher.Result?,
        visual: ReverseImageVisualMatcher.Outcome,
        notes: List<String>
    ): ReverseImageLookupResult {
        val gps = local.metadata?.gps
        val resolvedLocation = geo?.displayName ?: gps ?: web?.resolvedLocation
        val mapsUrl = when {
            gps != null -> "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(gps)}"
            web?.mapsUrl != null -> web.mapsUrl
            resolvedLocation != null ->
                "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(resolvedLocation)}"
            else -> null
        }
        val combinedEvidence = (geo?.evidence.orEmpty() + web?.evidence.orEmpty())
            .distinctBy { "${it.title}|${it.url}" }
            .take(MAX_LOCATION_EVIDENCE)
        val locationCandidates = buildLocationCandidates(
            gps = gps,
            resolvedLocation = resolvedLocation,
            geoEvidence = geo?.evidence.orEmpty(),
            webEvidence = web?.evidence.orEmpty(),
            labels = local.labels
        )
        val note = (notes + visual.note)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .take(MAX_NOTE_CHARS)

        return ReverseImageLookupResult(
            gps = gps,
            extractedText = local.extractedText,
            labels = local.labels,
            faceDetected = local.faceDetected,
            faceWarning = if (local.faceDetected) FACE_WARNING else null,
            resolvedLocation = resolvedLocation,
            mapsUrl = mapsUrl,
            webEvidence = combinedEvidence,
            visualMatches = visual.matches,
            visualCandidates = visual.candidates,
            visualClusters = visual.clusters,
            visualSearchNote = note.ifBlank { null },
            locationCandidates = locationCandidates
        )
    }

    private fun pendingVisualOutcome(): ReverseImageVisualMatcher.Outcome =
        ReverseImageVisualMatcher.Outcome(
            matches = emptyList(),
            note = "Public candidate comparison is still in progress; local media evidence is shown now.",
            candidateCount = 0
        )

    private fun unavailableVisualOutcome(note: String): ReverseImageVisualMatcher.Outcome =
        ReverseImageVisualMatcher.Outcome(
            matches = emptyList(),
            note = note,
            candidateCount = 0
        )

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
        const val LOCAL_STAGE_TIMEOUT_MS = 8_000L
        const val LOCATION_STAGE_TIMEOUT_MS = 20_000L
        const val VISUAL_STAGE_TIMEOUT_MS = 30_000L
        const val MAX_NOTE_CHARS = 2_000
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
