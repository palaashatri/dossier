package io.dossier.app.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device perceptual fingerprinting for exact copies, resized images, recompressed
 * images, screenshots, and modest crops. This deliberately does not perform facial
 * recognition or person identification.
 */
internal object VisualFingerprint {

    private const val MAX_DECODE_EDGE = 1024

    data class Fingerprint(
        val averageHash: Long,
        val differenceHash: Long,
        val perceptualHash: Long,
        val colorHistogram: FloatArray
    )

    data class FingerprintSet(
        val sha256: String,
        val variants: List<Fingerprint>,
        val width: Int,
        val height: Int
    )

    data class Similarity(
        val score: Float,
        val exactBytes: Boolean,
        val perceptual: Float,
        val difference: Float,
        val average: Float,
        val color: Float
    )

    fun fromBytes(bytes: ByteArray): FingerprintSet? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_EDGE || bounds.outHeight / sample > MAX_DECODE_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            fromBitmap(bitmap, sha256(bytes))
        } finally {
            bitmap.recycle()
        }
    }

    fun fromBitmap(bitmap: Bitmap, sha256: String = ""): FingerprintSet {
        val variants = buildList {
            add(fingerprint(bitmap))

            centerCrop(bitmap, 0.82f)?.let { crop ->
                try {
                    add(fingerprint(crop))
                } finally {
                    crop.recycle()
                }
            }

            squareCenterCrop(bitmap)?.let { crop ->
                try {
                    add(fingerprint(crop))
                } finally {
                    crop.recycle()
                }
            }
        }.distinctBy { Triple(it.averageHash, it.differenceHash, it.perceptualHash) }

        return FingerprintSet(
            sha256 = sha256,
            variants = variants,
            width = bitmap.width,
            height = bitmap.height
        )
    }

    fun compare(first: FingerprintSet, second: FingerprintSet): Similarity {
        if (first.sha256.isNotBlank() && first.sha256 == second.sha256) {
            return Similarity(1f, true, 1f, 1f, 1f, 1f)
        }

        var best = Similarity(0f, false, 0f, 0f, 0f, 0f)
        for (left in first.variants) {
            for (right in second.variants) {
                val perceptual = hashSimilarity(left.perceptualHash, right.perceptualHash)
                val difference = hashSimilarity(left.differenceHash, right.differenceHash)
                val average = hashSimilarity(left.averageHash, right.averageHash)
                val color = histogramIntersection(left.colorHistogram, right.colorHistogram)
                val score = (
                    perceptual * 0.55f +
                        difference * 0.24f +
                        average * 0.09f +
                        color * 0.12f
                    ).coerceIn(0f, 1f)
                if (score > best.score) {
                    best = Similarity(score, false, perceptual, difference, average, color)
                }
            }
        }
        return best
    }

    fun classify(score: Float, exactBytes: Boolean): String = when {
        exactBytes -> "Exact file copy"
        score >= 0.94f -> "Near-identical copy"
        score >= 0.87f -> "Likely resized or recompressed repost"
        score >= 0.80f -> "Probable visual duplicate"
        else -> "Possible visual similarity"
    }

    internal fun hashSimilarity(first: Long, second: Long): Float =
        1f - java.lang.Long.bitCount(first xor second) / 64f

    internal fun histogramIntersection(first: FloatArray, second: FloatArray): Float {
        val count = min(first.size, second.size)
        var sum = 0f
        for (index in 0 until count) sum += min(first[index], second[index])
        return sum.coerceIn(0f, 1f)
    }

    private fun fingerprint(bitmap: Bitmap): Fingerprint = Fingerprint(
        averageHash = averageHash(bitmap),
        differenceHash = differenceHash(bitmap),
        perceptualHash = perceptualHash(bitmap),
        colorHistogram = colorHistogram(bitmap)
    )

    private fun averageHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        return try {
            val values = grayscalePixels(scaled)
            val mean = values.average()
            var hash = 0L
            values.forEachIndexed { index, value ->
                if (value >= mean) hash = hash or (1L shl index)
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun differenceHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        return try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(scaled.getPixel(x, y)) >= luma(scaled.getPixel(x + 1, y))) {
                        hash = hash or (1L shl bit)
                    }
                    bit++
                }
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun perceptualHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        return try {
            val values = grayscalePixels(scaled)
            val lowFrequency = DoubleArray(64)
            var outputIndex = 0
            for (v in 0 until 8) {
                for (u in 0 until 8) {
                    var sum = 0.0
                    for (y in 0 until 32) {
                        for (x in 0 until 32) {
                            sum += values[y * 32 + x] *
                                cos((2 * x + 1) * u * PI / 64.0) *
                                cos((2 * y + 1) * v * PI / 64.0)
                        }
                    }
                    val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                    val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                    lowFrequency[outputIndex++] = 0.25 * cu * cv * sum
                }
            }

            val median = lowFrequency.drop(1).sorted().let { sorted ->
                sorted[sorted.size / 2]
            }
            var hash = 0L
            lowFrequency.forEachIndexed { index, value ->
                if (index != 0 && value >= median) hash = hash or (1L shl index)
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun colorHistogram(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        return try {
            val histogram = FloatArray(64)
            for (y in 0 until scaled.height) {
                for (x in 0 until scaled.width) {
                    val pixel = scaled.getPixel(x, y)
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    val bucket = (red / 64) * 16 + (green / 64) * 4 + (blue / 64)
                    histogram[bucket] += 1f
                }
            }
            val total = (scaled.width * scaled.height).toFloat().coerceAtLeast(1f)
            for (index in histogram.indices) histogram[index] /= total
            histogram
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun grayscalePixels(bitmap: Bitmap): DoubleArray {
        val result = DoubleArray(bitmap.width * bitmap.height)
        var index = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                result[index++] = luma(bitmap.getPixel(x, y))
            }
        }
        return result
    }

    private fun luma(pixel: Int): Double {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }

    private fun centerCrop(bitmap: Bitmap, fraction: Float): Bitmap? {
        val width = (bitmap.width * fraction).toInt().coerceAtLeast(1)
        val height = (bitmap.height * fraction).toInt().coerceAtLeast(1)
        if (width == bitmap.width && height == bitmap.height) return null
        val left = ((bitmap.width - width) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - height) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun squareCenterCrop(bitmap: Bitmap): Bitmap? {
        if (bitmap.width == bitmap.height) return null
        val side = min(bitmap.width, bitmap.height)
        val left = ((bitmap.width - side) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - side) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
