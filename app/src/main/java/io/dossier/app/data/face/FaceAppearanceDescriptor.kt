package io.dossier.app.data.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Dependency-free fallback descriptor for comparing already-detected face crops.
 *
 * This is deliberately narrower than biometric face recognition. It measures
 * the visual appearance of the supplied crops using low-frequency luminance,
 * colour histograms, and edge orientations. It is useful for detecting reused,
 * resized, or lightly recompressed profile photos when no FaceNet/ArcFace model
 * is installed, but must not be presented as proof that two different photos
 * show the same person.
 */
object FaceAppearanceDescriptor {
    private const val SIZE = 32
    private const val BLOCKS = 8
    private const val HIST_BINS = 8
    private const val EDGE_BINS = 8

    fun describe(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val luma = FloatArray(SIZE * SIZE)
        val redHist = FloatArray(HIST_BINS)
        val greenHist = FloatArray(HIST_BINS)
        val blueHist = FloatArray(HIST_BINS)

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val pixel = scaled.getPixel(x, y)
                val red = Color.red(pixel) / 255f
                val green = Color.green(pixel) / 255f
                val blue = Color.blue(pixel) / 255f
                luma[y * SIZE + x] = 0.299f * red + 0.587f * green + 0.114f * blue
                redHist[histogramBin(red)] += 1f
                greenHist[histogramBin(green)] += 1f
                blueHist[histogramBin(blue)] += 1f
            }
        }

        val blockMeans = FloatArray(BLOCKS * BLOCKS)
        val blockSize = SIZE / BLOCKS
        for (blockY in 0 until BLOCKS) {
            for (blockX in 0 until BLOCKS) {
                var sum = 0f
                for (dy in 0 until blockSize) {
                    for (dx in 0 until blockSize) {
                        val x = blockX * blockSize + dx
                        val y = blockY * blockSize + dy
                        sum += luma[y * SIZE + x]
                    }
                }
                blockMeans[blockY * BLOCKS + blockX] = sum / (blockSize * blockSize)
            }
        }

        val edgeHist = FloatArray(EDGE_BINS)
        for (y in 1 until SIZE - 1) {
            for (x in 1 until SIZE - 1) {
                val gx = luma[y * SIZE + x + 1] - luma[y * SIZE + x - 1]
                val gy = luma[(y + 1) * SIZE + x] - luma[(y - 1) * SIZE + x]
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude <= 0.01f) continue
                val angle = atan2(gy, gx).toDouble()
                val normalized = ((angle + Math.PI) / (2.0 * Math.PI)).coerceIn(0.0, 0.999999)
                val bin = floor(normalized * EDGE_BINS).toInt().coerceIn(0, EDGE_BINS - 1)
                edgeHist[bin] += magnitude
            }
        }

        val descriptor = FloatArray(blockMeans.size + redHist.size + greenHist.size + blueHist.size + edgeHist.size)
        var offset = 0
        blockMeans.copyInto(descriptor, offset)
        offset += blockMeans.size
        normalizeHistogram(redHist).copyInto(descriptor, offset)
        offset += redHist.size
        normalizeHistogram(greenHist).copyInto(descriptor, offset)
        offset += greenHist.size
        normalizeHistogram(blueHist).copyInto(descriptor, offset)
        offset += blueHist.size
        normalizeHistogram(edgeHist).copyInto(descriptor, offset)

        if (scaled !== bitmap) scaled.recycle()
        return l2Normalize(descriptor)
    }

    fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
        if (first.isEmpty() || first.size != second.size) return 0f
        // Descriptor values may originate from an imported/model backend. Keep
        // non-finite input or arithmetic from becoming persisted evidence.
        if (first.any { !it.isFinite() } || second.any { !it.isFinite() }) return 0f
        var dot = 0.0
        var normFirst = 0.0
        var normSecond = 0.0
        for (index in first.indices) {
            val a = first[index].toDouble()
            val b = second[index].toDouble()
            dot += a * b
            normFirst += a * a
            normSecond += b * b
        }
        if (!dot.isFinite() || !normFirst.isFinite() || !normSecond.isFinite()) return 0f
        if (normFirst <= 0.0 || normSecond <= 0.0) return 0f
        val score = dot / (sqrt(normFirst) * sqrt(normSecond))
        return if (score.isFinite()) score.toFloat().coerceIn(-1f, 1f) else 0f
    }

    private fun histogramBin(value: Float): Int =
        floor(value.coerceIn(0f, 0.999999f) * HIST_BINS).toInt().coerceIn(0, HIST_BINS - 1)

    private fun normalizeHistogram(values: FloatArray): FloatArray {
        val total = values.sum()
        if (total <= 0f) return values
        return FloatArray(values.size) { index -> values[index] / total }
    }

    private fun l2Normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.fold(0.0) { acc, value -> acc + value * value }).toFloat()
        if (norm <= 0f) return values
        return FloatArray(values.size) { index -> values[index] / norm }
    }
}
