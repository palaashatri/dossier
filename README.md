# Dossier

Consent-first Android privacy exposure auditing for your own public digital footprint.

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-111111?style=for-the-badge">
</p>

<p align="center">
  <img src="screenshots/emulator/01-consent.png" width="220" alt="Dossier consent screen">
  <img src="screenshots/emulator/08-report.png" width="220" alt="Dossier report screen">
  <img src="screenshots/emulator/10-models.png" width="220" alt="Dossier models screen">
</p>

## What Dossier Is

Dossier is a self-audit tool. You provide identity signals that you own or are explicitly authorized to audit, and the app looks for public exposure evidence across profile pages, search indexes, image indexes, breach metadata, and local media clues.

The app is intentionally evidence-oriented. A result can be verified, plausible, or review-only; it should not be treated as proof of identity or account ownership without manual confirmation.

## Current Capabilities

- Consent and identity-signal intake for name, usernames, aliases, emails, phones, locations, organizations, explicit profile URLs, and an optional selfie.
- Username variant generation and user-controlled variant selection.
- Public profile checks across common platforms such as GitHub, X, Reddit, LinkedIn, Instagram, YouTube, TikTok, Twitch, GitLab, Medium, Dev.to, Bluesky, Mastodon, Pinterest, Telegram, and Hacker News.
- Rendered WebView fallback for pages that need JavaScript before profile verification.
- One-hop pivot discovery from links and handles self-disclosed on confirmed profiles.
- Optional Deep Research that follows a small number of linked personal websites and fetches extra public evidence.
- Public-search discovery through DuckDuckGo, Bing, Google, and Yandex result pages.
- Public image-index discovery through Bing Images and DuckDuckGo image search using identity terms only.
- PII extraction for exposed emails, phones, names, aliases, locations, organizations, and sensitive snippets.
- Risk scoring, remediation guidance, and a shareable plain-text report.
- Breach checks:
  - Passwords use Have I Been Pwned Pwned Passwords k-anonymity range checks.
  - Emails can use HIBP breached-account metadata when the user supplies a HIBP API key.
  - Emails also run bounded public-search evidence checks.
- Reverse media lookup:
  - Images: EXIF GPS, on-device OCR, scene labels, face safety gate, and web search over extracted text/label clues.
  - Videos: samples a few local frames, extracts OCR/labels locally, flags faces as a safety gate, and searches only extracted clues.
- AI summary generation:
  - Local Gemma E2B/E4B MediaPipe LLM model files can be downloaded/imported.
  - Optional remote providers include OpenAI-compatible APIs, Anthropic, Ollama, Hugging Face Inference, and OpenRouter.
  - Remote provider model discovery is available where the provider exposes a model-list endpoint.

## Privacy And Network Behavior

Dossier has no project-hosted backend and no app telemetry, but it is not fully offline.

The following operations make network requests:

- Public profile checks and rendered-page verification.
- Search-index and image-index discovery.
- Reverse image/video location lookup when EXIF GPS is absent and text/label clues are searched.
- HIBP password range checks, which send only the first five SHA-1 hash characters.
- HIBP email breach checks when a HIBP API key is supplied.
- Optional remote AI summaries and model-list refreshes.
- Local model downloads from Hugging Face LiteRT Community URLs.

The following data stays local in the current design:

- Selfie and selected image/video bytes are not uploaded by the reverse media lookup pipeline.
- Video lookup samples frames locally and searches only OCR/label text.
- Face detection is used as a safety gate; the app does not perform public facial identification or face matching.
- Password plaintext is cleared from the breach screen after checks and is not included in results.

API keys for remote AI providers are currently saved in Android `SharedPreferences`. Treat this as convenience storage, not hardened secret storage.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="screenshots/emulator/01-consent.png" width="210" alt="Consent screen"><br><sub>Consent</sub></td>
    <td align="center"><img src="screenshots/emulator/02-identity-empty.png" width="210" alt="Identity input screen"><br><sub>Identity Input</sub></td>
    <td align="center"><img src="screenshots/emulator/06-username-discovery.png" width="210" alt="Username discovery screen"><br><sub>Username Discovery</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/emulator/07-scan-progress.png" width="210" alt="Scan progress screen"><br><sub>Scan Progress</sub></td>
    <td align="center"><img src="screenshots/emulator/08-report.png" width="210" alt="Report screen"><br><sub>Dossier Report</sub></td>
    <td align="center"><img src="screenshots/emulator/09-image-lookup.png" width="210" alt="Reverse media lookup screen"><br><sub>Media Lookup</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/emulator/10-models.png" width="210" alt="Models screen"><br><sub>Models</sub></td>
  </tr>
</table>

## Main User Flow

1. Accept the consent screen.
2. Enter identity signals.
3. Review generated username variants and add custom handles.
4. Run the exposure scan.
5. Review verified profiles, public-search review candidates, image evidence, extracted PII, risk level, AI/baseline summary, and remediation actions.
6. Open evidence in the built-in browser or share the plain-text report.

The bottom navigation also exposes:

- Media Lookup: reverse image/video location estimation from local metadata and extracted clues.
- Breach: HIBP password range checks, optional HIBP email breach metadata, and public email evidence search.
- Models: local model management and remote provider configuration.

## AI Provider Configuration

The Models screen supports both local and remote engines.

Local engines:

- ML Kit Vision is always available for OCR, face detection, and scene labeling.
- AICore is listed as best-effort and depends on device support.
- Gemma E2B and Gemma E4B use public Hugging Face LiteRT Community `.task` downloads or user-imported MediaPipe task files.
- PaliGemma is import-only and experimental in this build.

Remote providers:

- OpenAI/OpenAI-compatible: `{baseUrl}/chat/completions`, model list from `{baseUrl}/models`.
- Anthropic: `{baseUrl}/messages`, model list from `{baseUrl}/models`.
- Ollama: `{baseUrl}/api/chat`, model list from `{baseUrl}/api/tags`.
- Hugging Face: `{baseUrl}/models/{model}`, curated presets only.
- OpenRouter: OpenAI-compatible chat completions and model list from `/api/v1/models`.

If multiple remote providers are enabled, the first usable provider in the app's enum order is used. Prefer enabling only the provider you want until explicit provider priority is added.

## Build Locally

Requirements:

- Android Studio with Android SDK 35.
- JDK 21.
- Android device or emulator running Android 8.0+ (API 26+).

Android Studio normally creates `local.properties` with your SDK path. Do not commit it.

Run unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

Build a debug APK:

```sh
./gradlew :app:assembleDebug
```

Install on a connected emulator/device:

```sh
./gradlew :app:installDebug
```

Build an unsigned release APK:

```sh
./gradlew :app:assembleRelease
```

Clean generated outputs:

```sh
./gradlew clean
```

## Permissions

The app declares:

- `INTERNET` for public pages, search evidence, HIBP checks, model discovery, model downloads, and optional remote AI.
- `CAMERA` for optional consented image capture.
- `READ_MEDIA_IMAGES` for selected images on newer Android versions.
- `READ_MEDIA_VIDEO` for selected videos on newer Android versions.
- `READ_EXTERNAL_STORAGE` for Android 12 and below.

## Project Structure

```text
app/src/main/java/io/dossier/app/
  data/
    ai/        Remote/local AI adapters, provider config, model discovery
    breach/    HIBP password/email checks
    place/     EXIF, face, OCR, image labeling adapters
    platform/  Platform profile registry
    web/       Search, image search, web location, link-following helpers
  domain/
    ai/        Local model types and downloader
    breach/    Breach result models
    face/      Honest no-embedding face consistency stubs
    model/     Shared app models
    pii/       PII extraction
    place/     Reverse image/video orchestration
    risk/      Risk scoring
    scanner/   Profile scan session, scanner, WebView rendering, handle pivots
    username/  Username variant generation
  export/      Plain-text and JSON report helpers
  ui/          Compose screens, navigation, components, theme

app/src/test/java/io/dossier/app/
  Unit tests for model discovery, search parsing, image search parsing, breach parsing,
  scanner attribution, PII extraction, username variants, risk scoring, and video sampling.
```

## Known Limitations

- Public sites can block, rate-limit, challenge, hide content, or change markup at any time.
- Search-engine scraping is best-effort; Google, Yandex, Bing, and DuckDuckGo may return consent/challenge pages or markup the parser cannot read.
- The initial profile scan can still fan out heavily for many selected variants and platforms.
- Username Discovery currently stores selected variants as username seeds, so the scanner may expand them again.
- Search and image-index hits are review candidates, not verified ownership.
- Reverse media location is an estimate. If no strong place phrase is found, the maps link may be based on raw OCR/label query text.
- No public face recognition or visual identity matching is implemented. Face consistency scoring is intentionally disabled until a real embedding model and profile-avatar pipeline exist.
- API keys are stored in `SharedPreferences`, not Android Keystore.
- Remote AI calls send dossier summary prompts to the configured provider.
- Release signing automation is not currently included in the tracked repository.

## Audit Status

Last checked with:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The command passed locally. Gradle reported deprecation warnings related to future Gradle 10 compatibility.

## License

Apache License 2.0. See [LICENSE](LICENSE).
