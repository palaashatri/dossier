# Dossier

Dossier is a consent-first Android application for auditing a subject's public digital footprint when the subject owns the information, has consented, or the operator has another legitimate authorization.

It collects public evidence, preserves provenance, separates verification from review-only leads, correlates findings conservatively, and helps track remediation. Dossier is not a guarantee of complete internet coverage and does not treat search results, visual similarity, shared usernames, AI output, or graph connections as proof of identity or account ownership.

## Current status

The current implementation branch has reached **62/100 under the strict production rubric** after a validated product-contract tranche. The validated implementation commit passes provider-registry audit, face-calibration runtime validation, JVM tests, APK assembly, and 8 API 35 Compose instrumentation tests.

That does **not** mean production ready. The major remaining gates include provider scale/live validation, complete coordinator/frontier ownership, calibrated identity and face benchmarks, historical/image-cluster UX, and representative physical-device/accessibility/performance validation.

See `TRUTH.md` for the authoritative score, subsystem status, validation commit and blockers. `AGENTS.md` defines the target product contract.

## Capabilities

### Discovery Fabric

- Typed, declarative provider registry with **78 authored provider/service definitions** across public profiles, code/package, creative/media, search, archive and breach-related sources.
- Quick, Standard, Deep and Exhaustive scan modes backed by actual runtime provider plans rather than fake totals.
- Structured provider categories, query capabilities, source-reliability classes and request policies.
- Registry validation, duplicate/template/parser-drift checks and CI maintenance audit.
- Deterministic handling of present, not-found, soft-404, authentication-required, challenged, redirect, unexpected and invalid provider responses.
- General public-search acquisition across multiple providers with bounded budgets.
- Direct source-page verification before assigning stronger attribution.
- Provider retries, `Retry-After` handling, caches, circuit breakers and source canaries.
- Bounded two-hop identity pivots from corroborated handles and explicit public cross-links.
- Weak name/location/occupation/face-only signals do not recursively expand by themselves.
- Optional bounded public-link expansion.
- Exact-URL Internet Archive recovery for some deleted or replaced pages.
- Explicit verified, probable/candidate, historical, unavailable and rejected states.

The registry is not yet the contract's 1,000+ reviewed/live-validated provider set. Provider availability remains externally controlled.

### Live scan orchestration

- Central `ScanCoordinatorRuntime` wraps the mature vertical scan pipeline.
- Structured scan IDs, requests, states and events.
- Live UI state derives from actual scan-stage, profile, face, breach, graph and analysis observations.
- Cancellation is supported and fake per-provider completion events are deliberately not emitted.
- Selected scan mode survives the resumable-input marker.

True suspended pause/resume, provider-level queue/start/completion events, persisted frontier checkpoints and sole coordinator ownership are still in progress.

### Evidence, correlation and identity graph

- Names, usernames, aliases, emails, phone numbers, locations, organizations and explicit profile URLs.
- Attribution-aware PII extraction that does not elevate unrelated regex matches into strong identity claims.
- Universal evidence records can retain provider ID, source URL, retrieval/observation timestamps, verification state, source reliability, content hash, parser version and historical/current state.
- Numeric confidence is explicitly separate from verification state.
- Typed graph-v2 semantic node kinds and relationships are layered compatibly over the existing saved-case model.
- Graph nodes/edges can retain evidence IDs, contradiction IDs, history fields and verification/conflict state.
- Explainable multi-signal account resolution is integrated into the production graph.
- **A shared username alone is not sufficient to confirm identity.**
- Contradictory evidence is retained rather than ignored.
- Exposure dimensions, evidence pathways, risk prioritization and remediation guidance.

Entity-resolution weights remain engineering values until a representative benchmark establishes calibrated precision/recall and false-positive behavior.

### Reverse-image and visual checks

The selected reference image remains on-device. Public candidate images may be downloaded for local comparison.

- SHA-256 exact matching.
- pHash, dHash and aHash.
- Colour-histogram comparison.
- Full-image, centre-crop and square-crop variants.
- Classification of exact copies, near-identical images, resized/recompressed reposts and probable visual duplicates.
- Optional local YuNet/SFace cross-photo correlation using pinned OpenCV Zoo models.
- Exact model size/SHA-256 integrity verification and atomic installation.
- Explicit per-scan choice between strong local correlation and conservative basic appearance matching.
- Five-landmark alignment, ambiguity rejection, quality gates, cosine scoring and transient-memory cleanup.
- Calibration tooling with identity-disjoint evaluation support.

Candidate coverage is limited to images exposed by queried public sources. Face similarity remains supporting evidence and current reference thresholds are not advertised as measured identity probabilities.

### Historical evidence

- Bounded exact-URL Internet Archive lookup and snapshot verification.
- Historical evidence is labeled separately from current evidence.
- Historical confidence is capped rather than silently promoted to current truth.
- Timestamp-disciplined timeline construction uses only real evidence timestamps or provider breach dates.
- Untimestamped observations are omitted rather than assigned invented dates.
- Current evidence, archive evidence and breach incidents remain distinct event types.

Broad historical extraction and the production timeline UI remain incomplete.

### Breach checks

- Pwned Passwords five-character SHA-1 range lookup; the full password is not transmitted.
- HIBP email account range lookup when the user supplies supported credentials and the provider supports the flow.
- No silent fallback to sending a complete email address when the privacy-preserving account range flow is unavailable.
- Authoritative HIBP coverage remains separate from ordinary public-web mentions.
- Unconfigured, rejected, rate-limited and unavailable states are not presented as clean results.
- Breach date, provider, verification, retrieval and data-class metadata are preserved for provenance/timeline use.
- Dossier does not bundle or distribute stolen credential databases or leaked passwords.

### Evidence-grounded AI

- Deterministic local analysis is always available as a fallback.
- Optional local/device and user-configured remote engines.
- Retrieved page content is treated as untrusted evidence rather than instructions.
- Generated factual claims must conform to structured output and cite existing evidence IDs.
- Claims referencing nonexistent evidence IDs or making uncited factual assertions are rejected.
- Contradiction can downgrade a generated high-confidence claim.
- Malformed or unsupported generated output falls back to deterministic on-device analysis instead of being displayed raw.
- Remote API keys are encrypted with Android Keystore-backed AES-GCM.
- Remote processing remains opt-in and disclosed.

### Encrypted cases, corrections and remediation

- Active scan state is temporary by default.
- Explicitly saved cases use Android Keystore-backed AES-256-GCM.
- Versioned case schema, atomic writes, filesystem sync and integrity verification.
- Migration of legacy plaintext cases without a plaintext fallback for new saves.
- Case schema v3 supports authorized scope, scan history, user corrections, remediation records and export records.
- Saved-case comparison with explicit older/newer roles and deletion controls.
- Evidence decisions: **Mine / Not mine / Unsure / Ignore**.
- Account decisions: **This is me / Not me / Unsure**.
- Corrections affect the effective analysis/graph while preserving raw encrypted evidence.
- Remediation states: Not started, In progress, Submitted, Awaiting response, Completed, Rejected and Needs manual action.
- A completed workflow state does not claim the remote exposure has disappeared; a later scan must verify observable change.
- Differential comparison distinguishes added, removed, changed and unchanged findings.
- `NotObservedInLatestScan` is not presented as verified global deletion.

### Reporting and share-safe export

- Paginated PDF report plus machine-readable JSON evidence package.
- Per-section SHA-256 hashes and a canonical manifest hash.
- Explicit **Share-safe** export mode from saved Cases.
- Redaction occurs before export files are written.
- Share-safe mode removes/generalizes direct subject values, source URLs, evidence snippets, profile details, graph labels/details, breach identifiers, visual source URLs and generated analysis that may reproduce identifying evidence.
- Redacted JSON records the redaction mode.
- The UI warns that redaction reduces disclosure but does not guarantee anonymity; generated files should be reviewed before sharing.

## User experience

The main investigation report is organized into four areas:

1. **Overview** — exposure priority, inspected coverage, dimensions and highest-priority evidence.
2. **Evidence** — findings, profiles, visual comparisons, breach coverage and source links.
3. **Connections** — relationship graph, text-list alternative and evidence pathways.
4. **Actions** — remediation, encrypted saving, export, expanded scanning and session deletion.

Saved Cases add before/after comparison, correction decisions, remediation tracking and share-safe export.

The app uses Android's system Photo Picker, requests camera access only for optional capture, and does not request broad media-library permissions.

## Privacy and network behavior

Dossier has no required project-operated backend and does not include analytics telemetry. It is not fully offline.

Network operations can include:

- public profile and source-page checks;
- search and image-index acquisition;
- archive availability and snapshot retrieval;
- downloading public candidate images and optional local model packs;
- HIBP range queries;
- optional remote AI providers.

Local operations include:

- reference-image processing;
- exact and perceptual image comparison;
- YuNet/SFace detection, alignment, embeddings and scoring;
- PII parsing, graph construction, risk scoring and deterministic analysis;
- encrypted case storage and corrections/remediation state;
- PDF/JSON report generation and pre-write share-safe redaction.

No selected reference image, aligned face crop, landmark set or face embedding is intentionally uploaded by the visual-correlation pipeline.

## Build and validation

Requirements:

- JDK 21.
- Android SDK 35.
- Android Studio or the Gradle wrapper.
- Android 8.0 or newer; the debug build currently targets `arm64-v8a` for packaged native dependencies.

Core commands:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew connectedUiTestAndroidTest
python3 tools/provider_registry_audit.py
```

CI separately validates the pinned Python/OpenCV face-calibration environment.

The latest validated implementation commit and exact passing gates are recorded in `TRUTH.md`.

A release build requires private signing properties to produce a signed distributable artifact:

```sh
./gradlew :app:assembleRelease
```

Do not commit `local.properties`, keystores, credentials, API keys, personal test identities, developer-specific device paths or real-user screenshots.

## Repository layout

```text
app/src/main/java/io/dossier/app/
  data/       Network, discovery, AI, breach, visual-model and storage adapters
  domain/     Discovery, evidence, graph, correlation, risk, remediation and case logic
  export/     PDF and JSON evidence-package generation and redaction
  ui/         Compose navigation, screens, components and theme

app/src/test/java/io/dossier/app/
  JVM regression tests

app/src/androidTest/java/io/dossier/app/
  API-level Compose/integration tests

tools/
  Provider-registry audit and face-calibration/reproducibility utilities
```

## Known limitations

- The current declarative registry contains 78 definitions, not the 1,000+ reviewed/live-validated target.
- Private, authenticated, blocked, never-indexed and never-archived content cannot be discovered reliably.
- Public providers can change markup, challenge requests, rate-limit or omit content.
- Provider-level live event reporting, true pause/resume and persisted recursive-frontier recovery are incomplete.
- Entity resolution still needs a representative calibrated precision/recall/false-positive benchmark.
- Candidate-based image comparison cannot evaluate images no source exposed.
- Cross-photo face correlation still requires a sufficiently large consented/legal benchmark and measured ROC/FAR/FRR before production accuracy claims are justified.
- Historical extraction and timeline UX are incomplete.
- HIBP email coverage requires user-provided supported credentials and provider availability.
- Share-safe redaction reduces disclosure but cannot guarantee anonymity.
- Hash manifests provide integrity metadata but are not independent digital signatures or third-party attestations.
- Physical Samsung/Pixel/lower-memory, accessibility, font-scale, process-death, thermal, battery and large-case performance validation remain release gates.

## Documentation policy

The repository intentionally keeps only three Markdown documents:

- `README.md` — public product and build documentation.
- `AGENTS.md` — authoritative product/engineering contract.
- `TRUTH.md` — authoritative current status, validation record and remaining work.

Do not add separate status, roadmap, audit, handoff, findings or completion Markdown files. Update `TRUTH.md` instead.

## License

Apache License 2.0. See `LICENSE`.
