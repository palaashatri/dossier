# Dossier

Dossier is a consent-first Android privacy exposure audit app. It helps a user review their own public digital footprint by checking public profile pages, extracting exposed personal signals, estimating overall risk, and producing a locally generated report.

The app is built with Kotlin, Jetpack Compose, Material 3, and on-device vision tools. It has no app backend: scan state, report generation, and image analysis orchestration live on the device. Public profile checks and location evidence searches still make normal network requests to the public web.

## Features

- Guided consent and identity input flow for self-audits.
- Username variant generation from supplied handles or names.
- Public profile discovery across common platforms including GitHub, Instagram, X, Reddit, Stack Overflow, TikTok, YouTube, Medium, LinkedIn, Pinterest, Telegram, Bluesky, Mastodon, Dev.to, Twitch, GitLab, Hacker News, Threads, and Snapchat.
- Best-effort profile verification through HTTP checks and embedded WebView rendering.
- One-hop pivot discovery from self-disclosed links and handles.
- Optional Deep Research mode that follows linked personal websites for richer public signals.
- PII extraction for exposed emails, phone numbers, locations, organizations, usernames, profiles, and sensitive snippets.
- Risk scoring and remediation guidance.
- Shareable plain-text dossier report.
- Reverse image location lookup using EXIF GPS, ML Kit OCR, scene labels, and public-web search of extracted text/label clues.
- Face safety gate for image lookup: faces disable identity search while allowing location analysis to continue.
- On-device AI engine configuration screen. ML Kit Vision is the default; other engines are shown honestly based on availability.

## Privacy And Safety Model

Dossier is designed for self-consented audits.

- Use it only for yourself or for targets that explicitly consented.
- There is no telemetry or project-hosted backend in this app.
- Identity scan data is held in local session state and can be purged from the app flow.
- Image bytes stay on device during reverse image lookup. If GPS is absent, only extracted text and scene-label clues are searched on the public web.
- Face detection is used as a safety gate, not as public facial identification.
- Results are best-effort signals, not proof of identity or ownership.

## Tech Stack

- Kotlin 2.1.0
- Android Gradle Plugin 8.7.3
- Gradle wrapper 9.5.1
- Jetpack Compose with Material 3
- Navigation Compose
- Kotlinx Serialization
- OkHttp and Jsoup for public-page fetching/parsing
- Android WebView for rendered-page verification
- ML Kit face detection, text recognition, and image labeling
- MediaPipe Tasks dependencies for future local AI work
- Lottie Compose for app transitions
- JUnit unit tests

## Requirements

- Android Studio with Android SDK 35 installed.
- JDK 21.
- Android device or emulator running Android 8.0+ (API 26+).

Android Studio usually creates `local.properties` automatically with your local SDK path. Do not commit that file.

## Getting Started

Clone the repository and open it in Android Studio, or build from the command line:

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install the debug build on a connected device or emulator:

```sh
./gradlew :app:installDebug
```

Build an unsigned release APK:

```sh
./gradlew :app:assembleRelease
```

The unsigned release APK is written to:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Run unit tests:

```sh
./gradlew :app:testDebugUnitTest
```

Clean generated outputs:

```sh
./gradlew clean
```

## GitHub Release APK

The workflow at `.github/workflows/build-release-apk.yml` builds Dossier `0.1.0`.

To publish the APK to GitHub Releases, push the release tag:

```sh
git tag v0.1.0
git push origin v0.1.0
```

You can also run the workflow manually from GitHub Actions. The workflow uploads a debug APK artifact for quick testing, but GitHub Releases require a signed release APK. Unsigned release APKs are not installable on Android.

Create a release keystore locally:

```sh
keytool -genkeypair \
  -v \
  -keystore dossier-release.jks \
  -alias dossier \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Convert the keystore to a one-line base64 value for GitHub Secrets:

```sh
base64 < dossier-release.jks | tr -d '\n'
```

Add these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Use the keystore password you entered for `ANDROID_KEYSTORE_PASSWORD`, the alias `dossier` for `ANDROID_KEY_ALIAS`, and the key password you entered for `ANDROID_KEY_PASSWORD`.

## Project Structure

```text
app/src/main/java/io/dossier/app/
  data/          Platform registries, local cache, web and vision adapters
  domain/        Models, scanners, AI engine abstractions, risk and remediation logic
  export/        Report export/share logic
  ui/            Compose screens, navigation, theme, and reusable components

app/src/main/assets/
  compute.json
  investigate.json
  search.json
  web.json

app/src/test/java/io/dossier/app/
  Unit tests for extraction, risk scoring, username generation, web search, and profile belonging rules
```

## Main User Flow

1. Accept the consent screen.
2. Enter identity signals: name, primary username, email, optional aliases, phones, locations, organizations, profile URLs, and other usernames.
3. Review generated username variants and add custom handles.
4. Run the exposure scan.
5. Review the dossier report, exposure logs, risk level, and remediation tips.
6. Share the generated plain-text report if needed.

The bottom navigation also exposes:

- Image Lookup: estimate where an image was taken from local metadata and extracted clues.
- Models: inspect available on-device AI engines.

## Permissions

The app declares:

- `INTERNET` for public profile checks and web evidence lookup.
- `CAMERA` for capturing consented images.
- `READ_MEDIA_IMAGES` on newer Android versions.
- `READ_EXTERNAL_STORAGE` for Android 12 and below.

## Limitations

- Public sites may block, rate-limit, challenge, or hide profiles from automated checks.
- Some platforms require login or render incomplete public pages.
- Name-only scans are lower confidence than scans with explicit usernames or profile URLs.
- Face consistency matching is intentionally skipped unless real profile image scraping and a real embedding model are added.
- Optional LLM-style engines are not silently mocked; unavailable or gated engines remain unavailable in this build.

## License

Apache License 2.0. See [LICENSE](LICENSE).
