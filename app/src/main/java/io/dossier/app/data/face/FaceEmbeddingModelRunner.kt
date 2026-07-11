package io.dossier.app.data.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

class FaceEmbeddingModelRunner(private val modelFile: File) {

    fun embed(faceBitmap: Bitmap): FloatArray =
        when (modelFile.extension.lowercase()) {
            "onnx" -> OnnxFaceEmbeddingRunner(modelFile).use { it.embed(faceBitmap) }
            "tflite" -> TfliteFaceEmbeddingRunner(modelFile).use { it.embed(faceBitmap) }
            else -> error("Unsupported face model format: ${modelFile.extension}")
        }

    companion object {
        fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
            require(left.isNotEmpty()) { "Left embedding is empty." }
            require(left.size == right.size) { "Embedding sizes differ: ${left.size} vs ${right.size}." }
            var dot = 0f
            var leftNorm = 0f
            var rightNorm = 0f
            for (index in left.indices) {
                dot += left[index] * right[index]
                leftNorm += left[index] * left[index]
                rightNorm += right[index] * right[index]
            }
            if (leftNorm == 0f || rightNorm == 0f) return 0f
            return dot / (sqrt(leftNorm) * sqrt(rightNorm))
        }
    }
}

private class TfliteFaceEmbeddingRunner(modelFile: File) : Closeable {
    private val interpreter = Interpreter(modelFile)

    fun embed(faceBitmap: Bitmap): FloatArray {
        val inputShape = interpreter.getInputTensor(0).shape()
        val inputSpec = InputSpec.from(inputShape.map { it.toLong() }.toLongArray())
        val input = preprocess(faceBitmap, inputSpec)
        val inputBuffer = input.toDirectFloatBuffer()
        val outputSize = interpreter.getOutputTensor(0).shape().fold(1) { total, dim ->
            total * dim.coerceAtLeast(1)
        }
        val outputBuffer = ByteBuffer
            .allocateDirect(outputSize * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        val output = FloatArray(outputSize)
        outputBuffer.asFloatBuffer().get(output)
        return output
    }

    override fun close() {
        interpreter.close()
    }
}

private class OnnxFaceEmbeddingRunner(modelFile: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()
    private val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
        ?: error("ONNX face model input is not a tensor.")

    fun embed(faceBitmap: Bitmap): FloatArray {
        val inputSpec = InputSpec.from(inputInfo.shape)
        val input = preprocess(faceBitmap, inputSpec)
        val inputBuffer = FloatBuffer.wrap(input)
        OnnxTensor.createTensor(env, inputBuffer, inputSpec.resolvedShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return flattenFloats(result[0].value)
            }
        }
    }

    override fun close() {
        session.close()
    }
}

private data class InputSpec(
    val resolvedShape: LongArray,
    val width: Int,
    val height: Int,
    val channelsFirst: Boolean
) {
    companion object {
        fun from(shape: LongArray): InputSpec {
            require(shape.size == 4) { "Face model input must be rank 4, got ${shape.contentToString()}." }
            val dim1 = shape[1]
            val dim2 = shape[2]
            val dim3 = shape[3]
            val channelsFirst = dim1 == 3L || (dim1 <= 0L && dim3 != 3L)
            val channels = if (channelsFirst) dim1 else dim3
            require(channels <= 0L || channels == 3L) {
                "Face model input must have 3 color channels, got ${shape.contentToString()}."
            }
            val height = dimensionOrDefault(if (channelsFirst) dim2 else dim1)
            val width = dimensionOrDefault(if (channelsFirst) dim3 else dim2)
            val resolved = longArrayOf(
                1L,
                if (channelsFirst) 3L else height.toLong(),
                if (channelsFirst) height.toLong() else width.toLong(),
                if (channelsFirst) width.toLong() else 3L
            )
            return InputSpec(
                resolvedShape = resolved,
                width = width,
                height = height,
                channelsFirst = channelsFirst
            )
        }

        private fun dimensionOrDefault(value: Long): Int =
            if (value > 0L) value.toInt() else DEFAULT_FACE_INPUT_SIZE
    }
}

private fun preprocess(bitmap: Bitmap, spec: InputSpec): FloatArray {
    val resized = Bitmap.createScaledBitmap(bitmap, spec.width, spec.height, true)
    val output = FloatArray((spec.width * spec.height * 3))
    for (y in 0 until spec.height) {
        for (x in 0 until spec.width) {
            val pixel = resized.getPixel(x, y)
            val red = normalizeChannel((pixel shr 16) and 0xff)
            val green = normalizeChannel((pixel shr 8) and 0xff)
            val blue = normalizeChannel(pixel and 0xff)
            if (spec.channelsFirst) {
                val planeSize = spec.width * spec.height
                val pixelIndex = y * spec.width + x
                output[pixelIndex] = red
                output[planeSize + pixelIndex] = green
                output[(2 * planeSize) + pixelIndex] = blue
            } else {
                val offset = (y * spec.width + x) * 3
                output[offset] = red
                output[offset + 1] = green
                output[offset + 2] = blue
            }
        }
    }
    return output
}

private fun normalizeChannel(value: Int): Float =
    (value - 127.5f) / 128f

private fun FloatArray.toDirectFloatBuffer(): ByteBuffer {
    val buffer = ByteBuffer
        .allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    buffer.asFloatBuffer().put(this)
    buffer.rewind()
    return buffer
}

private fun flattenFloats(value: Any?): FloatArray =
    when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flattenFloats(it).asIterable() }.toFloatArray()
        is Number -> floatArrayOf(value.toFloat())
        else -> error("Unsupported face model output type: ${value?.javaClass?.name ?: "null"}")
    }

private const val DEFAULT_FACE_INPUT_SIZE = 112
