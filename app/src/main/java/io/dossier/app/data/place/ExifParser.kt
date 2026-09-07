package io.dossier.app.data.place

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream

/**
 * Bounded local EXIF extraction used as Dossier's ExifTool-equivalent path for
 * user-selected media. No image metadata is uploaded merely to read EXIF fields.
 */
class ExifParser(private val context: Context) {
    data class Metadata(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeMeters: Double? = null,
        /** Camera-local wall clock; do not assume UTC without an offset. */
        val capturedAt: String? = null,
        /** GPS date/time are UTC by EXIF convention and are safe for solar/weather corroboration. */
        val gpsDateStamp: String? = null,
        val gpsTimeStamp: String? = null,
        val make: String? = null,
        val model: String? = null,
        val software: String? = null,
        val orientation: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val focalLength: String? = null,
        val exposureTime: String? = null,
        val fNumber: String? = null,
        val iso: String? = null,
        val lensModel: String? = null,
        val artist: String? = null,
        val copyright: String? = null
    ) {
        val gps: String?
            get() = if (latitude != null && longitude != null) "$latitude, $longitude" else null
    }

    fun parseGps(uri: Uri): String? = parseMetadata(uri)?.gps

    fun parseMetadata(uri: Uri): Metadata? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                val hasGps = exif.getLatLong(latLong)
                Metadata(
                    latitude = latLong[0].toDouble().takeIf { hasGps },
                    longitude = latLong[1].toDouble().takeIf { hasGps },
                    altitudeMeters = exif.getAltitude(Double.NaN).takeUnless { it.isNaN() },
                    capturedAt = firstNonBlank(
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                        exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ),
                    gpsDateStamp = clean(exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)),
                    gpsTimeStamp = clean(exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)),
                    make = clean(exif.getAttribute(ExifInterface.TAG_MAKE)),
                    model = clean(exif.getAttribute(ExifInterface.TAG_MODEL)),
                    software = clean(exif.getAttribute(ExifInterface.TAG_SOFTWARE)),
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, Int.MIN_VALUE)
                        .takeUnless { it == Int.MIN_VALUE },
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, -1).takeIf { it > 0 },
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, -1).takeIf { it > 0 },
                    focalLength = clean(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)),
                    exposureTime = clean(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
                    fNumber = clean(exif.getAttribute(ExifInterface.TAG_F_NUMBER)),
                    iso = clean(exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)),
                    lensModel = clean(exif.getAttribute("LensModel")),
                    artist = clean(exif.getAttribute(ExifInterface.TAG_ARTIST)),
                    copyright = clean(exif.getAttribute(ExifInterface.TAG_COPYRIGHT))
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.asSequence().mapNotNull(::clean).firstOrNull()

    private fun clean(value: String?): String? = value
        ?.replace('\u0000', ' ')
        ?.trim()
        ?.take(256)
        ?.takeIf(String::isNotBlank)
}
