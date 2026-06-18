package io.dossier.app.data.place

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream

class ExifParser(private val context: Context) {
    fun parseGps(uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    "${latLong[0]}, ${latLong[1]}"
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
