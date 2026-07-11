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
    GEMMA_E2B(
        displayName = "Gemma E2B (MediaPipe LLM)",
        description = "Download from Hugging Face LiteRT Community or import a compatible Gemma E2B MediaPipe LLM model file. Runs locally for dossier summarization.",
        downloadable = true,
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task?download=true",
        fileName = "gemma-4-E2B-it-web.task"
    ),
    GEMMA_E4B(
        displayName = "Gemma E4B (MediaPipe LLM)",
        description = "Download from Hugging Face LiteRT Community or import a compatible Gemma E4B MediaPipe LLM model file. Runs locally for stronger summaries on capable devices.",
        downloadable = true,
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task?download=true",
        fileName = "gemma-4-E4B-it-web.task"
    ),
    PALIGEMMA(
        displayName = "MediaPipe Vision Labels",
        description = "Import a MediaPipe ImageClassifier or ObjectDetector model for supplemental local labels. Free-form multimodal scene descriptions use AICore (Gemini Nano), not this path.",
        downloadable = false,
        url = "",
        fileName = "paligemma.task"
    );

    companion object {
        /**
         * The recommended default engine. ML Kit Vision is always available and
         * provides the real on-device OCR/face/label capabilities the app relies on.
         */
        val DEFAULT: LocalAiModelType = MLKIT_VISION
    }
}
