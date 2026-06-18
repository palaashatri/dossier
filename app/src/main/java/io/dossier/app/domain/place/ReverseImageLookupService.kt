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
import kotlinx.coroutines.withContext

/**
 * Orchestrates the Reverse Image Lookup pipeline.
 *
 * 1. Face safety gate (FaceAnalyzer) — a detected face skips identity search
 *    but location lookup continues (AGENTS.md rule).
 * 2. EXIF GPS — strongest signal when present.
 * 3. On-device OCR (TextRecognizer) — visible-text clues.
 * 4. On-device scene labels (ImageLabeler) — landmark/scene clues.
 * 5. Public-web search (WebLocationSearcher) of the text/label clues → resolved
 *    location + maps link. Only text clues are searched; image bytes stay local.
 *
 * Returns a [ReverseImageLookupResult]. Every stage degrades gracefully: a
 * missing GPS or empty OCR just yields fewer clues; nothing is fabricated.
 */
class ReverseImageLookupService(private val context: Context) {

    suspend fun lookup(uri: Uri, deepResearch: Boolean = false): ReverseImageLookupResult = withContext(Dispatchers.IO) {
        val faceAnalyzer = FaceAnalyzer(context)
        val exifParser = ExifParser(context)
        val textRecognizer = TextRecognizer(context)
        val imageLabeler = ImageLabeler(context)
        val webSearcher = WebLocationSearcher(context)

        val faceResult = faceAnalyzer.analyze(uri)
        val faceDetected = faceResult.faceDetected

        val gps = exifParser.parseGps(uri)
        val extractedText = textRecognizer.recognize(uri)
        val labels = imageLabeler.label(uri)

        // GPS is the authoritative location signal — no web search needed.
        if (gps != null) {
            return@withContext ReverseImageLookupResult(
                gps = gps,
                extractedText = extractedText,
                labels = labels,
                faceDetected = faceDetected,
                faceWarning = if (faceDetected) FACE_WARNING else null,
                resolvedLocation = gps,
                mapsUrl = "https://www.google.com/maps/search/?api=1&query=$gps",
                webEvidence = emptyList()
            )
        }

        // No GPS — resolve location from text + label clues via public web search.
        // The face-safety gate disables *identity* search, but location lookup
        // continues per AGENTS.md.
        val webResult = webSearcher.search(extractedText, labels.map { it.text }, deepResearch = deepResearch)

        ReverseImageLookupResult(
            gps = null,
            extractedText = extractedText,
            labels = labels,
            faceDetected = faceDetected,
            faceWarning = if (faceDetected) FACE_WARNING else null,
            resolvedLocation = webResult.resolvedLocation,
            mapsUrl = webResult.mapsUrl ?: webResult.resolvedLocation?.let {
                "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(it)}"
            },
            webEvidence = webResult.evidence
        )
    }

    private companion object {
        const val FACE_WARNING =
            "Face detected — visual identity search disabled. Location lookup continues."
    }
}
