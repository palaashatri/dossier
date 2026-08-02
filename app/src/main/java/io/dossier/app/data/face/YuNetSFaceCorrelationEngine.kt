package io.dossier.app.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import io.dossier.app.domain.model.FaceConsistencyMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Exact OpenCV reference pipeline for strong, local cross-photo correlation:
 *
 * bounded EXIF-corrected decode -> YuNet detection and five landmarks ->
 * quality/ambiguity gates -> SFace alignCrop -> SFace feature -> cosine match.
 *
 * The detector and recognizer are loaded once per service and protected by a
 * mutex because OpenCV DNN instances are not treated as concurrently reusable.
 * Image matrices, aligned crops and embeddings are released after every pair.
 */
class YuNetSFaceCorrelationEngine(
    context: Context,
    private val modelPack: FaceCorrelationModelPack = FaceCorrelationModelPack(context),
    private val calibrationStore: FaceCorrelationCalibrationStore = FaceCorrelationCalibrationStore(context)
) {
    private val appContext = context.applicationContext
    private val inferenceMutex = Mutex()

    @Volatile
    private var loadedRuntime: Runtime? = null

    data class FaceQuality(
        val accepted: Boolean,
        val reason: String,
        val detectorScore: Float,
        val faceWidth: Float,
        val faceHeight: Float,
        val eyeDistance: Float,
        val rollDegrees: Float,
        val brightness: Float,
        val laplacianVariance: Float
    ) {
        fun summary(): String =
            "detector=${format(detectorScore)}, face=${faceWidth.toInt()}x${faceHeight.toInt()}, " +
                "eyes=${eyeDistance.toInt()}px, roll=${format(rollDegrees)}°, " +
                "brightness=${brightness.toInt()}, sharpness=${laplacianVariance.toInt()}"

        private fun format(value: Float): String = "%.2f".format(value)
    }

    private data class Runtime(
        val detector: FaceDetectorYN,
        val recognizer: FaceRecognizerSF
    )

    private data class PreparedFace(
        val sourceBgr: Mat,
        val faceRow: Mat,
        val aligned: Mat,
        val feature: Mat,
        val quality: FaceQuality
    ) : AutoCloseable {
        override fun close() {
            feature.release()
            aligned.release()
            faceRow.release()
            sourceBgr.release()
        }
    }

    private sealed class Preparation {
        data class Ready(val face: PreparedFace) : Preparation()
        data class Rejected(val reason: String) : Preparation()
    }

    private sealed class FaceSelection {
        data class Selected(val row: Int) : FaceSelection()
        data object None : FaceSelection()
        data object Ambiguous : FaceSelection()
    }

    suspend fun compare(
        selfieUri: Uri,
        profileUri: Uri,
        profileUrl: String
    ): FaceConsistencyMatch = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            compareLocked(selfieUri, profileUri, profileUrl, runtime())
        }
    }

    private fun compareLocked(
        selfieUri: Uri,
        profileUri: Uri,
        profileUrl: String,
        runtime: Runtime
    ): FaceConsistencyMatch {
        val selfie = prepare(selfieUri, "selected selfie", runtime)
        if (selfie is Preparation.Rejected) return rejected(profileUrl, selfie.reason)
        val selfieFace = (selfie as Preparation.Ready).face

        val profile = prepare(profileUri, "profile image", runtime)
        if (profile is Preparation.Rejected) {
            selfieFace.close()
            return rejected(profileUrl, profile.reason)
        }
        val profileFace = (profile as Preparation.Ready).face

        return try {
            val score = runtime.recognizer.match(
                selfieFace.feature,
                profileFace.feature,
                FaceRecognizerSF.FR_COSINE
            ).toFloat().coerceIn(-1f, 1f)
            val thresholds = calibrationStore.getThresholds()
            FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = score,
                warning = warningFor(
                    decision = thresholds.decision(score),
                    score = score,
                    thresholds = thresholds,
                    selfieQuality = selfieFace.quality,
                    profileQuality = profileFace.quality
                )
            )
        } finally {
            selfieFace.close()
            profileFace.close()
        }
    }

    private fun runtime(): Runtime {
        loadedRuntime?.let { return it }
        check(modelPack.isReady()) { "Verified YuNet/SFace model pack is not installed." }
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be initialized." }
        return Runtime(
            detector = FaceDetectorYN.create(
                modelPack.yunetModelFile().absolutePath,
                "",
                Size(DEFAULT_DETECTOR_SIZE, DEFAULT_DETECTOR_SIZE),
                DETECTION_SCORE_THRESHOLD,
                NMS_THRESHOLD,
                TOP_K
            ),
            recognizer = FaceRecognizerSF.create(
                modelPack.sfaceModelFile().absolutePath,
                ""
            )
        ).also { loadedRuntime = it }
    }

    private fun prepare(uri: Uri, label: String, runtime: Runtime): Preparation {
        val bitmap = loadBoundedOrientedBitmap(uri)
            ?: return Preparation.Rejected("$label could not be decoded safely; face correlation was not run.")
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            runtime.detector.setInputSize(bgr.size())
            runtime.detector.detect(bgr, faces)
            if (faces.empty() || faces.rows() <= 0 || faces.cols() < FACE_OUTPUT_COLUMNS) {
                bgr.release()
                return Preparation.Rejected("No usable face was detected in the $label; face correlation was not scored.")
            }

            val selectedIndex = when (val selection = selectFace(faces)) {
                is FaceSelection.Selected -> selection.row
                FaceSelection.None -> {
                    bgr.release()
                    return Preparation.Rejected(
                        "No face in the $label passed the detector-confidence and size gates."
                    )
                }
                FaceSelection.Ambiguous -> {
                    bgr.release()
                    return Preparation.Rejected(
                        "Multiple similarly prominent faces were detected in the $label. " +
                            "Choose an image containing one clear consenting subject."
                    )
                }
            }

            val faceRow = faces.row(selectedIndex).clone()
            val aligned = Mat()
            runtime.recognizer.alignCrop(bgr, faceRow, aligned)
            if (aligned.empty()) {
                faceRow.release()
                bgr.release()
                return Preparation.Rejected("Five-landmark alignment failed for the $label.")
            }

            val quality = evaluateQuality(faceRow, aligned, bgr.width(), bgr.height())
            if (!quality.accepted) {
                faceRow.release()
                aligned.release()
                bgr.release()
                return Preparation.Rejected(
                    "Insufficient $label quality: ${quality.reason}. ${quality.summary()}."
                )
            }

            val feature = Mat()
            runtime.recognizer.feature(aligned, feature)
            if (feature.empty()) {
                faceRow.release()
                aligned.release()
                feature.release()
                bgr.release()
                return Preparation.Rejected("SFace could not produce an embedding for the $label.")
            }
            val retainedFeature = feature.clone()
            feature.release()
            return Preparation.Ready(PreparedFace(bgr, faceRow, aligned, retainedFeature, quality))
        } catch (cancelled: CancellationException) {
            bgr.release()
            throw cancelled
        } catch (error: Exception) {
            bgr.release()
            return Preparation.Rejected(
                "$label processing failed: ${error.localizedMessage ?: error.javaClass.simpleName}."
            )
        } finally {
            faces.release()
            rgba.release()
            bitmap.recycle()
        }
    }

    /** A tiny background face does not invalidate one clear foreground subject. */
    private fun selectFace(faces: Mat): FaceSelection {
        val candidates = (0 until faces.rows()).mapNotNull { row ->
            val values = faces.get(row, 0) ?: return@mapNotNull null
            if (values.size < FACE_OUTPUT_COLUMNS) return@mapNotNull null
            val width = values[2].toFloat().coerceAtLeast(0f)
            val height = values[3].toFloat().coerceAtLeast(0f)
            val score = values[14].toFloat()
            if (width <= 0f || height <= 0f || score < MIN_ACCEPTED_DETECTOR_SCORE) return@mapNotNull null
            FaceCandidate(row, width * height, score)
        }.sortedByDescending { it.area * it.score }
        val first = candidates.firstOrNull() ?: return FaceSelection.None
        val second = candidates.getOrNull(1) ?: return FaceSelection.Selected(first.row)
        val similarlyProminent = second.area >= first.area * AMBIGUOUS_AREA_RATIO &&
            second.score >= first.score - AMBIGUOUS_SCORE_DELTA
        return if (similarlyProminent) FaceSelection.Ambiguous else FaceSelection.Selected(first.row)
    }

    internal fun evaluateQuality(
        faceRow: Mat,
        alignedFace: Mat,
        imageWidth: Int,
        imageHeight: Int
    ): FaceQuality {
        val values = faceRow.get(0, 0) ?: doubleArrayOf()
        if (values.size < FACE_OUTPUT_COLUMNS) {
            return FaceQuality(false, "invalid detector output", 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val width = values[2].toFloat()
        val height = values[3].toFloat()
        val detectorScore = values[14].toFloat()
        val rightEyeX = values[4].toFloat()
        val rightEyeY = values[5].toFloat()
        val leftEyeX = values[6].toFloat()
        val leftEyeY = values[7].toFloat()
        val eyeDistance = hypot(leftEyeX - rightEyeX, leftEyeY - rightEyeY)
        val roll = Math.toDegrees(
            atan2((leftEyeY - rightEyeY).toDouble(), (leftEyeX - rightEyeX).toDouble())
        ).toFloat()
        val faceAreaRatio = if (imageWidth > 0 && imageHeight > 0) {
            width * height / (imageWidth.toFloat() * imageHeight.toFloat())
        } else 0f

        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        var brightness = 0f
        var variance = 0f
        try {
            Imgproc.cvtColor(alignedFace, gray, Imgproc.COLOR_BGR2GRAY)
            brightness = Core.mean(gray).`val`[0].toFloat()
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)
            val sigma = stddev.get(0, 0)?.firstOrNull() ?: 0.0
            variance = (sigma * sigma).toFloat()
        } finally {
            stddev.release()
            mean.release()
            laplacian.release()
            gray.release()
        }

        val rejection = when {
            detectorScore < MIN_ACCEPTED_DETECTOR_SCORE -> "low detector confidence"
            width < MIN_FACE_DIMENSION || height < MIN_FACE_DIMENSION -> "face is too small"
            faceAreaRatio < MIN_FACE_AREA_RATIO -> "face occupies too little of the image"
            eyeDistance < MIN_EYE_DISTANCE -> "facial landmarks are too close for stable alignment"
            abs(roll) > MAX_ABS_ROLL_DEGREES -> "face rotation is too large"
            brightness !in MIN_BRIGHTNESS..MAX_BRIGHTNESS -> "face is severely underexposed or overexposed"
            variance < MIN_LAPLACIAN_VARIANCE -> "face crop is too blurred or compressed"
            else -> null
        }
        return FaceQuality(
            accepted = rejection == null,
            reason = rejection ?: "accepted",
            detectorScore = detectorScore,
            faceWidth = width,
            faceHeight = height,
            eyeDistance = eyeDistance,
            rollDegrees = roll,
            brightness = brightness,
            laplacianVariance = variance
        )
    }

    private fun warningFor(
        decision: String,
        score: Float,
        thresholds: FaceCorrelationThresholds,
        selfieQuality: FaceQuality,
        profileQuality: FaceQuality
    ): String {
        val prefix = "YuNet five-landmark alignment + SFace cosine=${"%.3f".format(score)}; " +
            "pipeline=${thresholds.pipelineVersion}; ${thresholds.summary()} "
        val quality = "Selfie quality [${selfieQuality.summary()}]; profile quality [${profileQuality.summary()}]. "
        val interpretation = when (decision) {
            FaceCorrelationDecision.HIGH_SIMILARITY -> if (thresholds.measured) {
                "Measured high visual similarity band. This is strong supporting evidence only; confirm ownership with independent identifiers."
            } else {
                "Reference high-band similarity. Dossier-specific false-match performance has not been measured, so manual review is required."
            }
            FaceCorrelationDecision.MANUAL_REVIEW -> if (thresholds.measured) {
                "Measured review-range similarity. Treat it only as supporting evidence and inspect independent signals."
            } else {
                "Reference review-band similarity. It does not alter risk until a matching measured calibration is installed."
            }
            else -> "No visual support for a cross-photo relationship at the active thresholds."
        }
        return prefix + quality + interpretation +
            " Images, aligned crops, landmarks, and embeddings remained on-device and were discarded after comparison."
    }

    private fun rejected(profileUrl: String, reason: String): FaceConsistencyMatch =
        FaceConsistencyMatch(
            profileUrl = profileUrl,
            similarityScore = 0f,
            warning = "$reason No identity conclusion was produced."
        )

    private fun loadBoundedOrientedBitmap(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sample > MAX_DECODE_DIMENSION
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching {
                ExifInterface(input).let { it.rotationDegrees to it.isFlipped }
            }.getOrDefault(0 to false)
        } ?: (0 to false)
        if (orientation.first == 0 && !orientation.second) return decoded
        val matrix = Matrix().apply {
            if (orientation.second) postScale(-1f, 1f)
            if (orientation.first != 0) postRotate(orientation.first.toFloat())
        }
        val transformed = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true
        )
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private data class FaceCandidate(val row: Int, val area: Float, val score: Float)

    companion object {
        private const val DEFAULT_DETECTOR_SIZE = 320.0
        private const val DETECTION_SCORE_THRESHOLD = 0.80f
        private const val MIN_ACCEPTED_DETECTOR_SCORE = 0.82f
        private const val NMS_THRESHOLD = 0.30f
        private const val TOP_K = 5_000
        private const val FACE_OUTPUT_COLUMNS = 15
        private const val MIN_FACE_DIMENSION = 72f
        private const val MIN_FACE_AREA_RATIO = 0.012f
        private const val MIN_EYE_DISTANCE = 20f
        private const val MAX_ABS_ROLL_DEGREES = 28f
        private const val MIN_BRIGHTNESS = 28f
        private const val MAX_BRIGHTNESS = 232f
        private const val MIN_LAPLACIAN_VARIANCE = 18f
        private const val AMBIGUOUS_AREA_RATIO = 0.58f
        private const val AMBIGUOUS_SCORE_DELTA = 0.08f
        private const val MAX_DECODE_DIMENSION = 1_600
    }
}
