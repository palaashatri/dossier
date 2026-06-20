package io.dossier.app.data.place

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import io.dossier.app.domain.model.ReverseImageLookupResult
import java.io.InputStream

/**
 * Real on-device scene/landmark labeling via ML Kit Image Labeling.
 *
 * The default bundled model runs fully on-device via Google Play Services — no
 * cloud, no image upload. Labels (e.g. "Mountain", "Beach", "Skyscraper",
 * "Monument", "Historical building") are used as location/scene clues by
 * Reverse Image Lookup.
 */
class ImageLabeler(private val context: Context) {

    // 0.5 confidence threshold — keep only reasonably certain labels as clues.
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    /** Returns labels with confidence >= threshold, or empty list on failure. */
    fun label(uri: Uri): List<ReverseImageLookupResult.ImageLabel> {
        val bitmap = loadBitmap(uri) ?: return emptyList()
        return label(bitmap)
    }

    /** Returns labels from an in-memory frame/bitmap. */
    fun label(bitmap: Bitmap): List<ReverseImageLookupResult.ImageLabel> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = labeler.process(inputImage)
        val labels = try {
            Tasks.await(task)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        return labels.map { ReverseImageLookupResult.ImageLabel(it.text, it.confidence) }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
