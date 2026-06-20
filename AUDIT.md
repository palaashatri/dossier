# Dossier — Code Audit

**Date**: 2026-06-21  
**Branch**: main  
**Version**: 0.1.0 (versionCode 1)  
**Auditor**: Claude Sonnet 4.6 via Claude Code

---

## Project Overview

Dossier is a consent-first Android privacy exposure auditing tool. Users enter their own identity signals — name, aliases, emails, phones, profile URLs, usernames — and the app searches for public exposure evidence across profile pages, search indexes, image indexes, breach metadata, and local media clues. All AI inference, image scanning, and risk scoring occur on-device or via explicitly user-configured remote endpoints.

**Tech stack**: Kotlin 2.1.0, Jetpack Compose + Material 3, OkHttp 4.12.0, Jsoup 1.18.3, ML Kit (Face Detection, Text Recognition, Image Labeling), MediaPipe Tasks Genai/Vision, Room 2.6.1 (in-memory only), kotlinx.serialization.

**Min SDK**: 26 (Android 8.0) — **Target SDK**: 35

---

## Architecture

Clean Architecture with three layers:

```
domain/     — Pure business logic: scanner, PII, risk, breach models, AI types, place orchestration
data/       — Infrastructure: HTTP adapters, platform registry, AI providers, EXIF/OCR/ML Kit wrappers
ui/         — Jetpack Compose screens, navigation, components, theme
export/     — Plain-text and JSON report generation
```

The dependency direction is correct: `data/` depends on `domain/`; `ui/` depends on `domain/` via ViewModels and `ScanSession`.

---

## File-by-File Summary

| File | Lines | Role | Notes |
|---|---|---|---|
| `domain/scanner/ProfileScanner.kt` | ~1168 | Four-pass scanner, two-stage verification, profile attribution | Largest file; attribution logic is a 165-line pure function for testability |
| `domain/scanner/ScanSession.kt` | ~182 | Stateful scan orchestration, MutableStateFlow state | Orchestrates four scanner passes, holds all result state |
| `domain/scanner/WebViewScraper.kt` | ~150 | WebView rendering for JS-heavy sites | Polls DOM stability, destroys WebView in `finally` |
| `domain/scanner/HandleExtractor.kt` | ~201 | Cross-platform handle pivot extraction | Static analysis only; extensive guards against false positives |
| `domain/username/UsernameVariantGenerator.kt` | ~99 | Username variant permutation | Deliberately constrained for single-word names |
| `domain/pii/PiiExtractor.kt` | ~304 | Regex PII extraction with identity-specific matching | 200+ hardcoded KNOWN_ORGS/KNOWN_LOCATIONS for disambiguation |
| `domain/place/ReverseImageLookupService.kt` | ~82 | Image lookup orchestration | EXIF → OCR → labels → web search; no image upload |
| `domain/place/ReverseVideoLookupService.kt` | ~120 | Video frame sampling + location lookup | Samples max 5 frames locally |
| `domain/ai/LocalAiModel.kt` | ~66 | Local AI engine enum with honest URLs | Prior version had fabricated/gated URLs; now corrected |
| `domain/ai/LocalAiModelDownloader.kt` | ~197 | Download with progress, temp-file strategy, size sanity check | Rejects files < 4KB; classifies 401 as gated-model error |
| `domain/face/FaceConsistencyChecker.kt` | ~28 | Face selfie vs. profile comparison | Calls `FaceEmbeddingService`; returns empty until real embeddings exist |
| `domain/face/FaceEmbeddingService.kt` | ~49 | Face comparison service | Returns `similarityScore = 0f`; prior version fabricated embeddings from box geometry + noise |
| `domain/risk/RiskScorer.kt` | ~31 | Risk elevation to highest severity found | Simple and correct |
| `domain/breach/BreachModels.kt` | ~33 | HIBP result models | |
| `domain/remediation/RemediationProvider.kt` | ~40 | Per-finding-type remediation guidance | |
| `data/ai/AiInsightService.kt` | ~288 | Remote + local AI summary orchestration | Supports OpenAI-compat, Anthropic, Ollama, HuggingFace, OpenRouter, Gemma LLM |
| `data/ai/AiCoreEngine.kt` | ~44 | AICore (Gemini Nano) engine | Returns `null` / `false` until real model handle exists; prior version fabricated results |
| `data/ai/MediaPipeEngine.kt` | ~128 | PaliGemma / downloaded vision model | Uses ObjectDetector; returns `null` on failure; prior version returned hardcoded mock strings |
| `data/ai/HybridAiClient.kt` | ~54 | Dispatches to AICore or MediaPipe based on selection | Falls back through chain; final fallback returns honest empty result |
| `data/ai/MediaPipeLlmTextEngine.kt` | ~43 | MediaPipe LLM inference for Gemma models | |
| `data/ai/AiModelDiscoveryService.kt` | — | Remote model list discovery | |
| `data/ai/AiProviderConfigStore.kt` | — | API key persistence in SharedPreferences | Acknowledged as non-hardened |
| `data/breach/BreachCheckService.kt` | ~241 | HIBP k-anonymity password + email breach checks | Without API key: falls back to public search evidence |
| `data/face/FaceEmbedder.kt` | ~60 | ML Kit Face Detection | Returns boolean; no biometric data retained |
| `data/local/ProfileConsistencyCache.kt` | ~63 | In-memory SQLite cache | `embedding` column and `insertEmbedding`/`getEmbedding` methods are dead code |
| `data/platform/PlatformRegistry.kt` | ~108 | 21-platform profile URL registry | Smart URL-to-profile resolution |
| `data/place/ExifParser.kt` | — | EXIF GPS extraction | Returns GPS string or null |
| `data/place/TextRecognizer.kt` | — | ML Kit OCR | |
| `data/place/ImageLabeler.kt` | — | ML Kit scene labeling | |
| `data/place/FaceAnalyzer.kt` | — | ML Kit face detection safety gate | |
| `data/web/PublicSearchDiscoveryService.kt` | ~150 | Public search via DuckDuckGo, Bing, Google, Yandex | Bounded: 18 default / 32 deep; scored; deduped |
| `data/web/PublicImageSearchService.kt` | — | Image-index discovery via Bing Images + DDG | Identity terms only; no image upload |
| `data/web/WebLocationSearcher.kt` | ~100 | Location resolution from text/label clues | DDG HTML; optional page fetch in deep research mode |
| `data/web/WebsiteLinkFollower.kt` | — | Follows personal website links from confirmed profiles | Bounded to max 3 websites |
| `export/ReportExporter.kt` | ~78 | Plain-text intelligence brief + JSON export | Intent-based sharing |
| `ui/navigation/DossierNavHost.kt` | — | Compose navigation host | |
| `ui/screens/*.kt` | — | All screens: Consent, Identity, Username Discovery, Scan, Report, Breach, Media Lookup, Models, Web Browser | |
| `ui/components/*.kt` | — | AnimatedBackground, GeminiSpark, HudComponents, ImageSourcePicker, LottieTransitions, SquigglyLoader | |
| `ui/theme/NeuralTheme.kt` | — | Custom Material 3 color palette + typography | |
| `app/build.gradle.kts` | ~97 | Build configuration | See issues below |

---

## Code Quality

### Strengths

- **Defensive programming throughout.** Guards, boundary checks, and graceful degradation are consistent across the scanner, PII extractor, downloader, and web services.
- **Honest limitations.** Prior code that fabricated results (AiCoreEngine, MediaPipeEngine, FaceEmbeddingService, LocalAiModel download URLs) has been replaced with explicit null returns and documented stubs. Comments cite the specific fabrication that was removed.
- **Clean architecture.** Domain, data, and UI layers are properly separated. `ProfileScanner.belongsToUserPure` is a pure function explicitly designed for unit testability.
- **Concurrency safety.** WebView renders are gated with `Semaphore(2)`. Coroutines use `awaitAll()` with per-deferred try/catch. Session state uses `MutableStateFlow`.
- **PII deduplication.** `distinctBy { it.type.name + it.value + it.sourceUrl }` prevents duplicate findings.
- **Download robustness.** Temp-file → rename-on-success strategy; rejects downloads < 4KB (catches error pages masquerading as model files).

### Issues Found

#### Build Configuration

| Severity | Issue | Location |
|---|---|---|
| Medium | `isMinifyEnabled = false` for release builds — APK ships unobfuscated | `app/build.gradle.kts:24` |
| Medium | Room compiler annotation processor is commented out (`// annotationProcessor("androidx.room:room-compiler:$roomVersion")`) — Room schema validation and compile-time query checking are disabled | `app/build.gradle.kts:66` |
| Low | No custom ProGuard rules beyond default — ML Kit, OkHttp, and Lottie may need explicit keep rules for release | `app/proguard-rules.pro` |
| Low | `versionCode = 1`, `versionName = "0.1.0"` — pre-release; no signing config in tracked files | `app/build.gradle.kts:17-18` |
| Low | `generativeai:0.9.0` dependency imported but not visibly used in main code paths | `app/build.gradle.kts:87` |

#### Dead Code

| Location | Dead Code | Impact |
|---|---|---|
| `ProfileConsistencyCache.kt:16-38` | `insertEmbedding()` and `getEmbedding()` never called | None; harmless but misleading |
| `ProfileConsistencyCache.kt:54` | `embedding TEXT` column schema never written | None |

#### Security

| Severity | Issue | Location |
|---|---|---|
| Low | API keys (OpenAI, Anthropic, Ollama, HuggingFace, OpenRouter, HIBP) stored in `SharedPreferences` | `data/ai/AiProviderConfigStore.kt` |
| Low | AICore package-presence check returns `false` anyway; logic is redundant | `data/ai/AiCoreEngine.kt:30-37` |

The README explicitly acknowledges the `SharedPreferences` limitation. No SQL injection vectors exist (parameterized queries used). No credentials appear in tracked source.

#### Minor

- Multiple call sites independently handle `http://` vs `https://` prefixing. A single `normalizeUrl()` utility would remove the duplication.
- `belongsToUserPure` is 165 lines long. It works and is unit-tested, but could be decomposed into named sub-predicates for readability.
- `KNOWN_ORGS` and `KNOWN_LOCATIONS` hardcoded in `PiiExtractor.kt` will drift over time. No mechanism to update them without a code change.

---

## Test Coverage

13 unit test files under `app/src/test/`:

| Test File | What it covers |
|---|---|
| `UsernameVariantGeneratorTest.kt` | Variant generation for single/multi-word names |
| `PiiExtractorTest.kt` | Regex patterns, identity-specific matching |
| `ProfileBelongingTest.kt` | `belongsToUserPure` attribution logic |
| `RiskScorerTest.kt` | Risk elevation rules |
| `HandleExtractorTest.kt` | Pivot handle extraction guards |
| `BreachCheckServiceTest.kt` | HIBP response parsing |
| `PublicSearchDiscoveryServiceTest.kt` | Search result parsing and scoring |
| `PublicImageSearchServiceTest.kt` | Image search result parsing |
| `WebLocationSearcherTest.kt` | Location resolution from clues |
| `ReverseVideoLookupServiceTest.kt` | Frame sampling and merge logic |
| `AiInsightServiceTest.kt` | Summary generation fallback chain |
| `AiModelDiscoveryServiceTest.kt` | Remote model list parsing |
| `LocalAiModelTest.kt` | Model availability checks |

**Not covered by unit tests:**
- Compose screen rendering
- Navigation flow end-to-end
- `WebViewScraper` (requires instrumented test with real Android runtime)
- Full scan session orchestration (integration test)
- `HybridAiClient` dispatch logic

---

## Permissions

Declared permissions are all justified by documented features:

| Permission | Justification |
|---|---|
| `INTERNET` | Profile checks, search, HIBP, model downloads, optional remote AI |
| `CAMERA` | Optional consented image capture |
| `READ_MEDIA_IMAGES` | User-selected images (Android 13+) |
| `READ_MEDIA_VIDEO` | User-selected videos (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Android 12 and below media access |

`allowBackup="false"` is set — no cloud backup of scan data.

---

## Dependency Versions

| Dependency | Version | Status |
|---|---|---|
| Kotlin | 2.1.0 | Current |
| Compose BOM | 2024.12.01 | Current at time of audit |
| OkHttp | 4.12.0 | Current |
| Jsoup | 1.18.3 | Current |
| ML Kit Face Detection | 16.1.7 | Current |
| ML Kit Text Recognition | 16.0.1 | Current |
| ML Kit Image Labeling | 17.0.9 | Current |
| MediaPipe Tasks Vision | 0.10.14 | Current |
| MediaPipe Tasks Genai | 0.10.14 | Current |
| Room | 2.6.1 | Current; KSP compiler absent |
| Lottie Compose | 6.3.0 | Current |
| `generativeai` | 0.9.0 | Imported but unused in observed code paths |
| ONNX Runtime | commented out | Planned for real face embeddings |
| TensorFlow Lite | commented out | Planned for real face embeddings |

---

## Privacy / Compliance Assessment

| Claim | Verified |
|---|---|
| No telemetry or user data exfiltration | Yes — no analytics SDK, no project-hosted backend |
| Image bytes not uploaded | Yes — `ReverseImageLookupService` processes locally; only OCR/label text is searched |
| Video processed locally | Yes — frames sampled on-device; only extracted text/labels leave the device |
| Passwords not stored | Yes — SHA-1 prefix only sent to HIBP; plaintext cleared after check |
| Face data not retained | Yes — `FaceEmbedder` returns boolean only; `FaceEmbeddingService` returns `similarityScore = 0f` |
| Session state purge on exit | Yes — `ScanSession.purge()` exists |
| No mass enumeration | Bounded: max 60 profile candidates pass 1, max 20 pivot candidates pass 2, 18-32 search queries pass 3 |

---

## Build Verification

Per README audit status:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Passed locally. Gradle deprecation warnings noted (future Gradle 10 compatibility). No test failures reported.

---

## Summary

The codebase is in good shape for a 0.1.0 pre-release. Core scanning, PII extraction, breach checking, media lookup, and AI summarization are all genuinely implemented. Prior instances of fabricated results (fake embeddings, hardcoded mock strings, gated download URLs) have been cleaned up with honest stubs. The main outstanding gaps are the absence of real face consistency scoring, an unwired AICore integration, and build configuration items that should be addressed before a public release (minify, Room compiler, ProGuard rules).
