package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.data.ai.HybridAiClient
import io.dossier.app.data.place.FaceAnalyzer
import io.dossier.app.data.place.ExifParser
import io.dossier.app.domain.model.PlaceScanResult

class PlaceImageScanner(
    private val context: Context,
    private val faceAnalyzer: FaceAnalyzer,
    private val exifParser: ExifParser,
    private val hybridAiClient: HybridAiClient
) {
    suspend fun scanPlaceImage(uri: Uri): PlaceScanResult {
        val faceResult = faceAnalyzer.analyze(uri)
        val gps = exifParser.parseGps(uri)
        
        val aiResult = hybridAiClient.analyzeImage(uri)
        val faceDetected = faceResult.faceDetected || aiResult.containsFace
        val locationQuery = generateLocationQuery(gps, aiResult.extractedText, aiResult.detectedLandmarks)

        if (faceDetected) {
            return PlaceScanResult(
                gps = gps,
                locationQuery = locationQuery,
                faceSkipped = true,
                faceWarning = "Face detected — visual identity search disabled",
                extractedText = aiResult.extractedText,
                detectedLandmarks = aiResult.detectedLandmarks
            )
        }

        return PlaceScanResult(
            gps = gps,
            locationQuery = locationQuery,
            faceSkipped = false,
            faceWarning = null,
            extractedText = aiResult.extractedText,
            detectedLandmarks = aiResult.detectedLandmarks
        )
    }

    private fun generateLocationQuery(gps: String?, ocrText: String?, landmarks: List<String>): String? {
        if (gps != null) {
            return "https://www.google.com/maps/search/?api=1&query=$gps"
        }
        
        val queryBuilder = StringBuilder()
        if (landmarks.isNotEmpty()) {
            queryBuilder.append(landmarks.joinToString(" "))
        }
        if (!ocrText.isNullOrBlank()) {
            val cleanOcr = ocrText.take(50).replace("[^a-zA-Z0-9 ]".toRegex(), "")
            if (cleanOcr.isNotBlank()) {
                if (queryBuilder.isNotEmpty()) queryBuilder.append(" ")
                queryBuilder.append(cleanOcr)
            }
        }
        
        val query = queryBuilder.toString().trim()
        if (query.isBlank()) return null
        
        return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"
    }
}
