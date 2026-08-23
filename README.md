# Dossier

Dossier is a consent-first Android application for auditing a subject's public digital footprint when the subject owns the information, has consented, or the operator has another legitimate authorization.

It collects public evidence, preserves provenance, separates verification from review-only leads, correlates findings conservatively, and helps track remediation. Dossier does not treat search results, shared usernames, image/face similarity, AI output, or graph connections as proof of identity or account ownership.

## Current status

The current implementation branch is **63/100 under the strict production rubric**.

Validated implementation commit:

```text
898ed3a28c4507e5674d7f2a80c3f51065b3cd07
```

That exact implementation passed the current final-tree validation gates:

```text
Provider registry audit             PASS — 78 definitions (70 profile templates + 8 services)
JVM unit tests                      PASS — 319 tests / 76 suites / 0 failures, errors, or skips
Debug APK assembly                  PASS — 114,932,558 bytes
Android lint                        PASS — 0 errors / 63 warnings / 6 hints
API 36 x86_64 instrumentation       PASS — 15/15 tests
```

The API 36 Medium Phone emulator run included 2/2 direct-current-row WorkManager database checks that confirm new work input/output contains only opaque identifiers and safe status values, plus 2/2 Android Keystore-backed resume-store checks. This proves the current WorkManager row shape, not historical-row erasure, SQLite WAL/free-page sanitization, restart reconciliation, reboot recovery, or physical-device behavior.

This is not a production-readiness claim. Provider scale/live validation, calibrated identity and face benchmarks, complete coordinator/frontier ownership, historical/case-integrated image workflows, and representative physical-device/accessibility/performance validation remain release gates.

See `TRUTH.md` for the authoritative score and blockers. `AGENTS.md` defines the target product contract.

## Discovery Fabric

- Typed declarative provider registry with **78 authored provider/service definitions**.
- Developer, social, forum, gaming, creative, publishing, professional, media, commerce, education, code/package, personal-site, archive, breach and search categories.
- Quick, Standard, Deep and Exhaustive scan modes backed by real provider plans rather than fake totals.
- Provider query capabilities, source-reliability classes, existence/extraction rules and request policies.
- Registry validation for duplicates, malformed templates, parser drift and inventory drift.
- Deterministic response states for present, not-found, soft-404, authentication-required, challenged, redirect, unexpected and invalid responses.
- Multiple bounded public-search sources, direct source verification, retries, `Retry-After`, caches and circuit breakers.
- Bounded two-hop pivots with admission rules: weak name/location/occupation/face-only signals do not recursively expand by themselves.
- Exact-URL Internet Archive recovery for some deleted/replaced pages.

The long-term contract calls for 1,000+ useful reviewed definitions. The current 78 definitions are not presented as registry-wide live validated.

## Scan orchestration and history

- `ScanCoordinatorRuntime` wraps the mature vertical scan pipeline.
- Structured scan IDs, requests, run states and events.
- Live UI state derives from real scan-stage/profile/face/breach/graph/analysis observations.
- Cancellation is supported; Dossier does not invent provider-level completion events while the underlying scheduler lacks callbacks.
- Identity seeds, scan mode, deep-scan choice and per-scan face policy are stored in an Android Keystore AES-GCM resume record; new WorkManager requests receive only its opaque UUID.
- Background WorkManager progress and failures are reduced to fixed stage/error codes rather than persisting arbitrary exception or identity text.
- Terminal scan lifecycle stores actual start/end time, mode, plan size, result counts and cancellation state.
- A SHA-256 fingerprint of normalized seed values binds a completed scan to the matching initial explicit encrypted case save without maintaining a duplicate plaintext identity cache.
- Later case edits cannot silently attach a newer scan to an older case.

True suspended pause/resume, provider-level queue/start/completion events, persisted frontier checkpoints, startup owner/WorkManager reconciliation and sole coordinator ownership remain incomplete.

## Evidence, graph and entity resolution

- Identity seeds: names, usernames, aliases, emails, phone numbers, locations, organizations and explicit profile URLs.
- Attribution-aware PII extraction.
- Evidence records can retain provider ID, URL, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical/current state.
- Numeric confidence is separate from verification state.
- Graph v2 provides semantic node kinds, typed relationships, evidence IDs, contradiction IDs, history fields and verification/conflict state while retaining saved-case compatibility.
- Multi-signal account resolution is integrated into the production graph.
- **A shared username alone is not sufficient to confirm identity.**
- Contradictory evidence is preserved.

Entity-resolution weights remain engineering values until a representative benchmark establishes precision, recall, false-positive/false-negative behavior and calibration.

## Reverse-image and visual checks

The selected reference image stays on-device. Dossier may search text/identity clues, download public candidate images, and compare those public images locally.

Implemented whole-image duplicate/repost analysis:

- SHA-256 exact matching;
- pHash, dHash and aHash;
- colour-histogram comparison;
- full-image, centre-crop and square-crop variants;
- exact-copy, near-identical, resized/recompressed and modest-crop detection;
- stable candidate IDs;
- per-candidate provenance containing source, source page, acquisition query, compared URL, retrieval timestamp, content/perceptual hashes, dimensions, comparison score and outcome;
- truthful candidate states for unavailable download, decode failure, compared/no-match and match;
- deterministic exact-content and perceptual-near-duplicate clusters with stable IDs;
- Reverse Media UI for candidate-state totals, cluster summaries, hash/dimension details and source drill-down.

Whole-image clusters mean **duplicate/reposted image content**. They do not mean two different photos depict the same person.

Optional local cross-photo face support uses pinned YuNet/SFace models with exact size/SHA-256 verification, deterministic preprocessing, five-landmark alignment, ambiguity/quality rejection and cosine scoring. Face similarity remains supporting evidence; release thresholds are not advertised as measured identity probabilities until a representative benchmark exists.

Image candidate/cluster objects are not yet persisted into encrypted cases or the primary identity graph across the normal investigation workflow.

## Historical evidence

- Bounded exact-URL Wayback lookup and snapshot verification.
- Historical evidence is labeled separately from current evidence.
- Historical confidence is capped.
- Timeline construction uses only real evidence timestamps or provider breach dates.
- Untimestamped observations are omitted rather than assigned fabricated dates.

Broad historical extraction and production timeline UI remain incomplete.

## Breach checks

- Pwned Passwords five-character SHA-1 range lookup; the full password is not transmitted.
- HIBP account range lookup when user-supplied supported access is available.
- No silent fallback to sending a complete email address when the privacy-preserving account-range flow is unavailable.
- Authoritative breach coverage remains distinct from general public-web exposure.
- Not-configured, rejected, rate-limited and unavailable states are explicit.
- Breach dates, provider/retrieval metadata and data classes are retained.
- Dossier does not bundle or distribute stolen credential databases or leaked passwords.

## Evidence-grounded AI

- Deterministic local analysis is always available as fallback.
- Optional local/device and user-configured remote engines.
- Retrieved content is treated as untrusted evidence, not instructions.
- Generated factual claims must use structured output and cite existing evidence IDs.
- Claims referring to nonexistent evidence IDs or making uncited factual assertions are rejected.
- Contradiction can downgrade a generated high-confidence claim.
- Malformed/unsupported generated output falls back to deterministic analysis instead of being displayed raw.
- Remote processing remains opt-in and disclosed.

A production AI evaluation corpus and fully corrected-graph/remediation-native model inputs remain incomplete.

## Encrypted cases, corrections and remediation

- Active scan state is temporary by default.
- Explicit saves use Android Keystore-backed AES-256-GCM.
- Versioned case schema, atomic writes and integrity verification.
- Legacy plaintext migration without a plaintext fallback for new saves.
- Case schema v3 supports authorized scope, scan history, user corrections, remediation records and export records.
- Saved-case comparison uses explicit older/newer roles.
- Evidence decisions: **Mine / Not mine / Unsure / Ignore**.
- Account decisions: **This is me / Not me / Unsure**.
- Corrections affect effective analysis/graph membership without deleting raw encrypted evidence.
- Remediation states: Not started, In progress, Submitted, Awaiting response, Completed, Rejected and Needs manual action.
- Differential comparison classifies added, removed, changed and unchanged findings.
- Recheck UI distinguishes **Still observed**, **Not observed in latest scan**, **Workflow status changed** and **Not rechecked**.
- `Not observed in latest scan` explicitly does **not** mean verified deletion from every live page, search index, cache or archive.

## Reports and share-safe export

- Paginated PDF plus machine-readable JSON evidence package.
- Per-section SHA-256 hashes and canonical manifest hash.
- Explicit `ShareSafe` mode from saved Cases.
- Redaction happens **before export files are written**.
- Share-safe mode removes/generalizes direct subject values, source URLs, snippets, profile details, graph labels/details, breach identifiers, visual source URLs and generated analysis that may reproduce identifying evidence.
- JSON records redaction mode.
- UI warns that redaction reduces disclosure but cannot guarantee anonymity; generated files should be reviewed before sharing.

## Privacy and network behavior

Dossier has no required project-operated backend and does not include analytics telemetry. It is not fully offline.

Network-dependent operations can include public profile/source checks, search/image-index acquisition, archive retrieval, public candidate-image/model downloads, HIBP range queries and optional remote AI.

Local operations include reference-image processing, exact/perceptual image comparison, YuNet/SFace inference, PII parsing, graph/risk analysis, encrypted case and scan-checkpoint state, report generation and share-safe redaction.

The reverse-image matcher identifies itself generically as Dossier rather than impersonating a consumer browser/device. Challenge pages and source restrictions are reported rather than bypassed.

## Build and validation

Requirements:

- JDK 21
- Android SDK 35
- Android Studio or Gradle wrapper
- Android 8.0+

Core checks:

```sh
python3 tools/provider_registry_audit.py
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew connectedUiTestAndroidTest
```

CI separately validates the pinned Python/OpenCV face-calibration environment.

A release build requires private signing properties:

```sh
./gradlew :app:assembleRelease
```

Do not commit `local.properties`, keystores, credentials, API keys, personal test identities, developer-specific paths or real-user screenshots.

## Known limitations

- 78 declarative definitions is not the 1,000+ reviewed/live-validated provider target.
- Private, authenticated, blocked, never-indexed and never-archived content cannot be discovered reliably.
- Providers can change markup, challenge requests, rate-limit or omit content.
- Provider-level live events, true pause/resume and persisted recursive-frontier recovery are incomplete.
- Pre-upgrade WorkManager rows may retain legacy raw scan input until WorkManager pruning; new rows are opaque, but no forensic SQLite/WAL erasure claim is made.
- Power-loss/startup reconciliation between the encrypted request pointer, owner marker and WorkManager state is incomplete; cancellation/cleanup operation failures are not yet durably reconciled.
- Entity resolution still needs a calibrated representative benchmark.
- Image provenance/clusters are not yet persisted into encrypted cases/identity graph for cross-account or cross-scan investigation.
- Cross-photo face correlation still requires measured ROC/FAR/FRR and representative physical-device validation.
- Historical extraction and timeline UX are incomplete.
- HIBP email coverage depends on user-supplied supported access and provider availability.
- Share-safe redaction reduces disclosure but cannot guarantee anonymity.
- Emulator CI cannot replace Samsung/Pixel/lower-memory, accessibility, font-scale, process-death, thermal, battery and large-case validation.

## Documentation policy

The repository intentionally keeps only three Markdown documents:

- `README.md` — public product/build documentation.
- `AGENTS.md` — authoritative product/engineering contract.
- `TRUTH.md` — authoritative current status, validation record and remaining work.

Do not add separate status, roadmap, audit, handoff, findings or completion Markdown files. Update `TRUTH.md` instead.

## License

Apache License 2.0. See `LICENSE`.
