package io.dossier.app.data.place

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.InputStream

/**
 * Real on-device OCR via ML Kit Text Recognition (Latin script).
 *
 * The model ships through Google Play Services and runs fully on-device — no
 * cloud, no image upload. Used by Reverse Image Lookup to read signs, signage,
 * storefronts, and other visible-text clues from a place image.
 */
class TextRecognizer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Returns the recognized text, or null if recognition failed/produced nothing. */
    fun recognize(uri: Uri): String? {
        val bitmap = loadBitmap(uri) ?: return null
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = recognizer.process(inputImage)
        val result = try {
            Tasks.await(task)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        val text = result.text.trim()
        return text.ifBlank { null }
    }

    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
