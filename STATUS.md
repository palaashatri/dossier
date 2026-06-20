# Dossier — Feature Status

**Date**: 2026-06-21  
**Version**: 0.1.0

---

## Fully Implemented

- Consent screen with explicit self-audit agreement
- Identity signal intake: name, aliases, emails, phones, locations, organizations, profile URLs, usernames, optional selfie
- Username variant generation (multi-word names only; single-word names return exact match to prevent false positives)
- User-controlled variant selection and custom handle entry
- Four-pass profile scanner:
  - Pass 1: Platform fan-out across 21 registered platforms (capped at 60 candidates)
  - Pass 2: One-hop pivot discovery from confirmed-profile handles and links (capped at 20)
  - Pass 3: Public search discovery via DuckDuckGo, Bing, Google, Yandex (18 queries default, 32 with Deep Research)
  - Pass 4: Public image-index discovery via Bing Images and DuckDuckGo image search
- Two-stage profile verification: OkHttp pre-filter (fast 404/soft-404 disqualification) → WebView confirm (JS-heavy sites)
- Optional Deep Research: follows linked personal websites (max 3 per confirmed profile), fetches extra evidence
- Profile attribution logic (`belongsToUserPure`): pure function checking name/email/phone/alias/handle matches with false-positive guards
- PII extraction: emails, phones, names, aliases, locations, organizations; identity-specific exposure detection
- Risk scoring: elevates to highest severity found (Critical > High > Medium > Low)
- Remediation guidance per finding type
- Plain-text intelligence brief export and JSON export; Intent-based share
- Breach checking:
  - Passwords: HIBP k-anonymity range check (only first 5 SHA-1 chars sent)
  - Emails: HIBP breached-account metadata with optional API key; falls back to public search evidence without key
- Reverse image lookup: EXIF GPS → on-device OCR → on-device scene labels → web search over text/label clues; face detection as safety gate; no image upload
- Reverse video lookup: local frame sampling (max 5 frames) → per-frame OCR/labels → face gate → web search over merged clues
- AI summary generation:
  - Local: Gemma E2B and Gemma E4B via MediaPipe LLM (downloadable)
  - Remote: OpenAI-compatible, Anthropic, Ollama, Hugging Face Inference, OpenRouter
  - Baseline text summary when no AI is configured
- Remote provider model-list discovery (where provider exposes endpoint)
- Local model management: download with progress, import from device storage, delete, size sanity check
- In-app browser for opening evidence URLs
- Session purge on exit

---

## Unimplemented

### Face Consistency Scoring

**Status**: Intentionally disabled stub.

`FaceConsistencyChecker` and `FaceEmbeddingService` exist but return `similarityScore = 0f` and an honest warning. The prior implementation fabricated 128-dimensional embeddings from bounding-box geometry plus random noise, which produced meaningless but numerically plausible-looking scores. That code was removed.

A real implementation requires:
1. A genuine face embedding model (FaceNet or equivalent, ONNX or TFLite format — both are commented-out dependencies in `build.gradle.kts:88-89`)
2. A profile-image download pipeline (the scanner scrapes profile image URLs but does not fetch the image bytes for comparison)
3. Cosine or L2 similarity scoring against a selfie embedding

The `FaceConsistencyChecker.checkSelfieVsProfiles()` signature and the `FaceConsistencyMatch` model are in place; they will work once a real embedding service is wired.

### AICore Integration

**Status**: Shell present, not wired.

`AiCoreEngine` detects the Android System Intelligence package (`com.google.android.as`) but returns `isAvailable() = false` regardless, because the actual on-device model handle for Gemma Nano is not wired. A real integration would use the `com.google.ai.client.generativeai` SDK (`generativeai:0.9.0` is already imported) with a device-local model handle. This is device-dependent (Pixel 8+ / select models).

### PaliGemma Download

**Status**: Import-only; no public download URL.

`LocalAiModelType.PALIGEMMA` has `downloadable = true` but an empty `url`. The UI download button will produce an error. Users can import a compatible MediaPipe task file from device storage. This is working as designed until a public LiteRT URL exists.

### Provider Priority Configuration

**Status**: Not implemented.

When multiple remote AI providers are enabled, the app uses the first usable one in enum order. There is no UI for reordering or assigning explicit priority. The README notes this limitation and recommends enabling only one provider at a time.

### Release Signing

**Status**: Not tracked.

No signing config is in the tracked repository. Building a distributable APK requires manually configuring a keystore.

---

## Partially Implemented

### ProfileConsistencyCache

`ProfileConsistencyCache` creates an in-memory SQLite database with a `consistency_cache(url TEXT, embedding TEXT)` table. The `insertEmbedding()` and `getEmbedding()` methods exist but are never called — they were written for the face consistency pipeline that was subsequently disabled. The cache is still created and cleared at scan start, which is harmless. The dead methods and unused column can be removed when the face consistency feature is genuinely implemented or definitively dropped.

### MediaPipeEngine Vision

`MediaPipeEngine` is wired for PaliGemma or any bundled `.tflite` asset and correctly degrades to `null` on failure. However it uses `ObjectDetector` rather than a multimodal vision LLM, so it produces object-detection labels rather than scene descriptions. This is adequate for the current role (supplement to ML Kit labels) but not equivalent to a real vision LLM.

### Room Integration

Room is imported (`room-runtime:2.6.1`, `room-ktx:2.6.1`) but the annotation processor / KSP compiler is commented out in `build.gradle.kts:66`. The current usage (raw `SQLiteOpenHelper`) works at runtime, but Room's compile-time schema validation, migration support, and query safety checking are unavailable. If Room DAOs or `@Entity` annotations are ever used, the compiler must be re-enabled.

---

## Possible Improvements

### High Value

- **Enable minification for release builds.** `isMinifyEnabled = false` ships an unobfuscated APK. Enable with appropriate keep rules for ML Kit, OkHttp, Lottie, and Jsoup. (`app/build.gradle.kts:24`)
- **Android Keystore for API keys.** HIBP, remote AI provider keys, and any future credentials are currently in `SharedPreferences`. `EncryptedSharedPreferences` (Jetpack Security) or the Android Keystore would harden secret storage with minimal API change.
- **Implement real face consistency.** Wire FaceNet/ONNX (already commented out as a dependency) into `FaceEmbeddingService`. Profile image bytes need to be fetched during the scan pass that already scrapes image URLs. The surrounding scaffolding (`FaceConsistencyChecker`, `FaceConsistencyMatch`, `ProfileConsistencyCache`) is in place.
- **Wire AICore.** The `generativeai` SDK is already imported. On supported devices this would provide a no-download-required local LLM path for AI summaries.

### Medium Value

- **KSP / Room compiler.** Uncomment `ksp("androidx.room:room-compiler:$roomVersion")` (changing from `annotationProcessor` to `ksp`), add the KSP plugin. This enables compile-time query checking if Room is used beyond the current raw SQLite helper.
- **URL normalization utility.** Several call sites independently handle scheme prefixing (`https://`, `http://`). Extract to a single `normalizeUrl(url: String): String` function.
- **Provider priority UI.** Add drag-to-reorder or numbered priority on the Models screen so users with multiple remote providers configured get predictable behavior.
- **Externalize KNOWN_ORGS / KNOWN_LOCATIONS.** These 200+ entry lists in `PiiExtractor.kt` will drift. Moving them to a bundled JSON asset allows updates without a code change.
- **Decompose `belongsToUserPure`.** The 165-line attribution function is correct and unit-tested, but breaking it into named sub-predicates (`hasNameMatch`, `hasEmailMatch`, etc.) would improve readability and make individual attribution signals easier to tune independently.

### Low Value / Housekeeping

- **Remove dead code in `ProfileConsistencyCache`.** `insertEmbedding()`, `getEmbedding()`, and the `embedding TEXT` column serve no current purpose. Remove them or replace with the real face-embedding pipeline when it lands.
- **Remove or use `generativeai` dependency.** If AICore integration remains unwired, this import adds APK size for no benefit.
- **ProGuard rules.** `proguard-rules.pro` likely needs explicit keep rules for ML Kit, OkHttp, Jsoup, and Lottie before release minification is enabled.
- **Gradle 10 deprecation warnings.** The build reports deprecation warnings related to future Gradle 10 compatibility. Addressing these now prevents build breakage on Gradle upgrade.
- **Instrumented tests.** `WebViewScraper`, end-to-end scan flow, and Compose screen behavior currently have no test coverage. Even a minimal smoke test for the scan session would catch regressions in the orchestration layer.
- **`worm_agents.md` references a pre-seeded `hibp-breach-index-2025.json` asset** and a `web-signals-config.json` whitelist. Neither file appears in the tracked repository. The live code uses HIBP API calls rather than a bundled index. The spec doc is outdated and should be updated to match the actual implementation.
