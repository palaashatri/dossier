package io.dossier.app.domain.ai

/**
 * Local AI engines the app can use.
 *
 * HONESTY NOTE: the previous version pointed at fabricated/gated Hugging Face
 * URLs ("gemma-4-e2b-it-mediapipe", "paligemma-3b-pt-448-tflite") that return
 * HTTP 401, then silently wrote a 1KB dummy file pretending the download
 * succeeded. Those are removed. Each entry now truthfully describes whether it
 * is actually usable on a given device.
 */
enum class LocalAiModelType(
    val displayName: String,
    val description: String,
    /** Whether this engine requires (and supports) a public file download. */
    val downloadable: Boolean,
    /** Public, ungated URL for the model file, or empty if none is available. */
    val url: String,
    /** Local filename once downloaded. */
    val fileName: String
) {
    MLKIT_VISION(
        displayName = "ML Kit Vision (On-Device)",
        description = "Real on-device OCR, face detection & scene labeling via Google Play Services. Always available offline. No download required.",
        downloadable = false,
        url = "",
        fileName = ""
    ),
    AICORE(
        displayName = "AICore (System Gemini Nano)",
        description = "On-device LLM via Android System Intelligence. Available only on supported devices (Pixel 8+ and select others). Best-effort.",
        downloadable = false,
        url = "",
        fileName = ""
    ),
    GEMMA_4_E2B(
        displayName = "Gemma 2B (Edge LLM)",
        description = "Gated model — no public ungated download URL is available. Shown for completeness; not downloadable in this build.",
        downloadable = false,
        url = "",
        fileName = "gemma-2b.tflite"
    ),
    PALIGEMMA(
        displayName = "PaliGemma (Vision Edge)",
        description = "Gated model — no public ungated download URL is available. Shown for completeness; not downloadable in this build.",
        downloadable = false,
        url = "",
        fileName = "paligemma.tflite"
    );

    companion object {
        /**
         * The recommended default engine. ML Kit Vision is always available and
         * provides the real on-device OCR/face/label capabilities the app relies on.
         */
        val DEFAULT: LocalAiModelType = MLKIT_VISION
    }
}
