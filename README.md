# Dossier

Dossier is a consent-first Android application for auditing a subject's public digital footprint when the subject owns the information, has consented, or the operator has another legitimate authorization.

It collects public evidence, preserves provenance, separates verification from review-only leads, and produces remediation-oriented reports. Dossier is not a guarantee of complete internet coverage and does not treat search results, visual similarity, or graph connections as proof of identity or account ownership.

## Current status

The current development branch contains the reliability, privacy, evidence-integrity, local face-correlation, and UI/UX hardening work tracked in pull request #2.

The exact branch head is expected to pass:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The calibration utility is validated separately in CI with its pinned Python/OpenCV environment.

See `TRUTH.md` for the authoritative implementation status, measured limitations, and remaining release gates. See `AGENTS.md` for repository engineering rules.

## Capabilities

### Public discovery

- Structured checks for supported public profile services.
- General public-search acquisition across multiple providers with bounded budgets.
- Direct source-page verification before assigning strong confidence.
- Provider retries, `Retry-After` handling, caches, circuit breakers, and source canaries.
- One-hop identity pivots from handles and links disclosed by confirmed sources.
- Optional bounded public-link expansion.
- Exact-URL Internet Archive recovery for some deleted or replaced pages.
- Explicit verified, historical, review-only, unavailable, not-found, and index-only states.

### Identity and evidence correlation

- Names, usernames, aliases, emails, phone numbers, locations, organizations, and explicit profile URLs.
- Attribution-aware PII extraction that does not elevate unrelated regex matches into strong identity claims.
- Evidence graph construction with explainable relationships and confidence contributors.
- Exposure dimensions, evidence pathways, risk prioritization, and remediation guidance.

### Reverse-image and visual checks

The selected reference image remains on-device. Public candidate images may be downloaded for local comparison.

- SHA-256 exact matching.
- pHash, dHash, and aHash.
- Colour-histogram comparison.
- Full-image, centre-crop, and square-crop variants.
- Classification of exact copies, near-identical images, resized/recompressed reposts, and probable visual duplicates.
- Optional local YuNet/SFace cross-photo correlation using pinned OpenCV Zoo models.
- Explicit per-scan choice between strong local correlation and conservative basic appearance matching.
- Five-landmark alignment, ambiguity rejection, quality gates, cosine scoring, and transient-memory cleanup.
- Reference-policy scores remain manual-review evidence unless a matching measured calibration is imported.

Candidate coverage is limited to images exposed by the queried public sources. A missing result does not prove that an image never appeared online.

### Breach checks

- Pwned Passwords five-character SHA-1 range lookup; the full password is not transmitted.
- HIBP email account range lookup when the user supplies supported credentials.
- Authoritative HIBP coverage remains separate from ordinary public-web mentions.
- Unconfigured, rejected, rate-limited, and unavailable states are not presented as a clean result.

### AI analysis

- Deterministic local analysis is always available as a fallback.
- Optional local Gemma and supported device AI engines.
- Optional user-configured remote providers.
- Every analysis identifies its engine and whether network analysis was used.
- Retrieved page content is treated as untrusted evidence rather than instructions.
- Remote API keys are encrypted with Android Keystore-backed AES-GCM.

### Persistence and reporting

- Active scan state is temporary by default.
- Explicitly saved cases use Android Keystore-backed AES-256-GCM.
- Versioned case schema, atomic writes, filesystem sync, and integrity verification.
- Migration of legacy plaintext cases without a plaintext fallback for new saves.
- Paginated PDF report plus machine-readable JSON evidence package.
- Per-section SHA-256 hashes and a canonical manifest hash.
- Saved-case comparison with explicit older/newer roles and deletion controls.

## User experience

The main report is organized into four views:

1. **Overview** — exposure priority, inspected coverage, dimensions, and highest-priority evidence.
2. **Evidence** — findings, profiles, visual comparisons, breach coverage, and source links.
3. **Connections** — relationship graph, text-list alternative, and evidence pathways.
4. **Actions** — remediation, encrypted saving, export, expanded scanning, and session deletion.

The app uses Android's system Photo Picker, requests camera access only for optional capture, and does not request broad media-library permissions.

## Privacy and network behavior

Dossier has no required project-operated backend and does not include analytics telemetry. It is not fully offline.

Network operations can include:

- Public profile and source-page checks.
- Search and image-index acquisition.
- Archive availability and snapshot retrieval.
- Downloading public candidate images and optional local model packs.
- HIBP range queries.
- Optional remote AI providers.

Local-only operations include:

- Reference-image processing.
- Exact and perceptual image comparison.
- YuNet/SFace detection, alignment, embeddings, and scoring.
- PII parsing, graph construction, risk scoring, deterministic analysis, case encryption, and report generation.

No selected reference image, aligned face crop, landmark set, or face embedding is intentionally uploaded by the visual-correlation pipeline.

## Build

Requirements:

- JDK 21.
- Android SDK 35.
- Android Studio or the Gradle wrapper.
- Android 8.0 or newer; the debug build currently targets `arm64-v8a`.

Commands:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

A release build requires private signing properties to produce a signed distributable artifact:

```sh
./gradlew :app:assembleRelease
```

Do not commit `local.properties`, keystores, credentials, API keys, personal test identities, device paths, or real-user screenshots.

## Repository layout

```text
app/src/main/java/io/dossier/app/
  data/      Network, AI, breach, visual-model, and storage adapters
  domain/    Evidence, scanning, correlation, risk, remediation, and case logic
  export/    PDF and JSON evidence-package generation
  ui/        Compose navigation, screens, components, and theme

app/src/test/java/io/dossier/app/
  JVM regression tests

tools/
  Face-calibration and reproducibility utilities
```

## Known limitations

- Private, authenticated, blocked, never-indexed, and never-archived content cannot be discovered reliably.
- Public providers can change markup, challenge requests, rate-limit, or omit content.
- Candidate-based image comparison cannot evaluate images no source exposed.
- Cross-photo face correlation still requires a sufficiently large consented, identity-disjoint calibration and test corpus before a high-confidence operating claim is justified.
- HIBP email coverage requires user-provided supported credentials.
- Hash manifests provide integrity metadata but are not independent digital signatures or third-party attestations.
- Real-device visual, accessibility, font-scale, thermal, battery, and longitudinal provider testing remain release gates.

## Documentation policy

The repository intentionally keeps only three Markdown documents:

- `README.md` — public product and build documentation.
- `AGENTS.md` — instructions for coding agents and maintainers.
- `TRUTH.md` — authoritative current status, audit conclusions, and remaining work.

Do not add separate status, roadmap, audit, handoff, findings, or completion Markdown files. Update `TRUTH.md` instead.

## License

Apache License 2.0. See `LICENSE`.
