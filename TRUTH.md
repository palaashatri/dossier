# Dossier — Implementation Truth

This is the single authoritative status, audit, roadmap, and limitation document for Dossier. Update this file whenever implementation reality changes. Do not create additional audit or status Markdown files.

## Snapshot

Dossier is an Android privacy-audit application for self-audits, consenting subjects, and other legitimate authorized use. It combines public-source acquisition, local evidence processing, attribution-aware correlation, remediation, encrypted case storage, and evidence export.

Current engineering assessment:

| Area | Status |
|---|---|
| Public discovery architecture | Strong, multi-provider, bounded, still externally dependent |
| Reverse-image verification | Implemented locally; candidate recall remains index-bounded |
| Cross-photo face correlation | YuNet/SFace implementation present and calibration-ready |
| Attribution and false-positive controls | Stronger, evidence-aware, still needs corpus measurement |
| Scan lifecycle | Major navigation/cancellation defects corrected |
| Breach semantics | Authoritative and public-web channels separated |
| AI provenance and prompt safety | Implemented with deterministic fallback |
| Saved cases | Versioned and encrypted with no new plaintext fallback |
| PDF/JSON reporting | Implemented with hash manifest |
| Core UI/UX architecture | Reworked around evidence and actions |
| Production/release readiness | Not yet established by real-device and longitudinal testing |

No literal 10/10 claim is justified.

## Implemented reliability controls

### Public discovery

- General search rotates across multiple public providers with bounded budgets.
- Supported profile services use structured resolution where available.
- Provider-specific parsers, retries, `Retry-After`, caches, circuit breakers, and scheduled canaries are present.
- Direct source-page verification is required before strong confidence.
- Independent-provider corroboration can strengthen a lead.
- Verified, historical, review-only, unavailable, not-found, and index-only states remain distinct.
- Exact-URL Wayback availability and snapshot retrieval can recover some deleted or replaced pages.
- Archive evidence must independently contain an identity signal and remains capped below current-page evidence.
- Dossier does not automatically submit pages to an archive.

### Reverse-image verification

The selected reference image stays local. Dossier acquires public candidate images and compares them on-device using:

- SHA-256.
- pHash.
- dHash.
- aHash.
- Colour-histogram intersection.
- Full-image, centre-crop, and square-crop variants.

Outputs distinguish exact copies, near-identical images, resized/recompressed reposts, and probable visual duplicates. Recall is limited by candidate sources.

### Cross-photo face correlation

The strong local path uses official OpenCV Zoo YuNet and SFace models.

Implemented controls:

- Exact model SHA-256 and expected-size pins.
- Explicit user-triggered installation.
- Temporary download files, bounded streaming, filesystem sync, verification, and atomic promotion.
- Full hash verification before first strong inference in each process.
- Bounded decode and orientation handling.
- YuNet detection and five facial landmarks.
- Ambiguous-group rejection.
- Face-size, image-area, eye-distance, roll, exposure, blur, and compression gates.
- `FaceRecognizerSF.alignCrop` and SFace feature extraction.
- Cosine scoring.
- Immediate release of transient source matrices, aligned crops, landmarks, and embeddings.
- Explicit per-scan choice between strong local correlation and basic appearance matching.
- Process-local strong-mode state reset after completion, cancellation, invalid input, or installation failure.
- Conservative basic descriptor fallback for near-identical/reused-image appearance.

Reference-policy scores remain manual-review evidence. They do not justify a biometric identity claim.

The calibration utility mirrors the Android YuNet/alignment/SFace pipeline and supports:

- Identity-disjoint calibration and test splits.
- Untouched held-out evaluation.
- FMR, TMR, FNMR, ROC/DET-oriented output, and bootstrap confidence intervals.
- Optional demographic and device slices.
- Quality-rejection counts and reasons.
- Hash- and pipeline-bound calibration JSON.

A high-confidence operating claim still requires a sufficiently large, consented, representative corpus and independently reproducible real-device results.

### Attribution and PII

- Exact user-supplied email and phone matches may receive high confidence.
- Generic emails remain review evidence unless independent identity signals connect the page to the subject.
- Generic phone candidates require phone context and remain low confidence without attribution.
- Dates, counters, and repeated-digit noise are rejected.
- Name, alias, location, and organization evidence considers URL handles and independent corroboration.
- Evidence snippets identify exact, corroborated, or unconfirmed attribution.

### Scan lifecycle

- Fabricated fallback identities were removed.
- Missing or unusable input shows a recovery state.
- Cancellation routes away from report completion.
- Completion navigation is guarded against double navigation and cancellation races.
- Bottom navigation is hidden while the scan route is active to prevent route disposal/restart.
- Consent is removed from the Back stack after acceptance.
- WebView-backed acquisition tears down deterministically and propagates coroutine cancellation.

Some synchronous network calls remain bounded primarily by timeouts rather than being fully asynchronous/cancellation-aware. Migrating all remaining calls is still desirable.

### Breach checks

- Authoritative HIBP account coverage and ordinary public-web exposure are separate.
- Password checks use the five-character Pwned Passwords SHA-1 range flow.
- Email account range checks use the supported six-character flow when compatible user credentials are provided.
- Complete passwords are not sent.
- The UI preserves exact password whitespace and clears plaintext field state before network work.
- Not configured, rejected, rate-limited, and unavailable states do not become “clear.”

Authoritative email coverage still requires user-provided supported credentials.

### AI

- Deterministic local analysis is the guaranteed fallback.
- Local model and remote-provider paths are optional.
- Every result identifies the engine and whether network analysis occurred.
- Evidence is bounded, sanitized, and enclosed as untrusted content.
- Remote endpoints require HTTPS except explicitly configured local/private Ollama use.
- Remote provider keys are encrypted with Android Keystore-backed AES-GCM.

### Persistence and export

Saved cases use:

- Android Keystore-backed AES-256-GCM.
- Random IVs.
- Versioned schema.
- Atomic temporary writes and filesystem sync.
- Plaintext integrity verification.
- Legacy plaintext migration.
- No plaintext fallback for new saves.

Exports include:

- Paginated PDF.
- Machine-readable JSON evidence package.
- UTC generation metadata.
- Section SHA-256 hashes.
- Canonical manifest hash.
- Findings, sources, confidence, profiles, visual results, analysis provenance, graph summary, and breach summary.

The manifest is not digitally signed and is not independent third-party attestation.

## UI/UX truth

Implemented source-level improvements:

- Accurate network/local/persistence consent disclosure.
- Saveable, responsive identity setup with visible validation.
- Multiline identity-signal input and removable reference photo.
- Explicit bounded public-link expansion disclosure.
- Correct older/newer saved-case roles and risk-delta colors.
- Confirmed case deletion.
- Report split into Overview, Evidence, Connections, and Actions.
- Risk and attribution confidence shown separately.
- Empty results no longer imply a clean privacy profile.
- Restricted evidence WebView with JavaScript/storage/file access disabled by default.
- History-aware Back, copy, reload, and external-open controls.
- Relationship graph coordinate normalization, two-axis scrolling, wrapping legend, and text alternative.
- Stronger semantic light/dark colors.
- Reduced-motion-aware route animation.
- System Photo Picker instead of broad media permissions.
- Branded adaptive launcher icon.
- Calmer terminology in UI and exports.

Current source-level UX assessment is roughly 8/10, not a formal usability score.

Before a 9.5–10/10 UI/UX claim:

1. Capture and review rendered screens on the target Samsung device plus small and large Android viewports.
2. Test 100%, 130%, 160%, and 200% font/display scaling.
3. Run TalkBack, switch access, keyboard navigation, and automated accessibility checks.
4. Measure rendered contrast, including disabled states.
5. Test process death, rotation, low-memory recreation, camera cancellation, picker fallback, and external-browser return.
6. Add Compose instrumentation and screenshot regression tests for primary flows.
7. Conduct real-user comprehension testing for evidence states, confidence, risk, and remediation.
8. Consolidate the remaining legacy engine-management and reverse-media utility visual language.
9. Move remaining user-visible strings into Android resources and test localization/RTL.

## Verification

Expected CI gates for the current hardening branch:

- Pinned Python/OpenCV calibration runtime validation.
- Kotlin/JVM unit tests.
- Android/Kotlin compilation.
- Minified debug APK assembly with R8.

Local commands:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Passing these gates proves compilation and covered behavior; it does not prove provider availability, real-device inference quality, usability, accessibility, battery behavior, or complete discovery coverage.

## Remaining roadmap

Priority order:

1. Run full real-device QA on the target Samsung device.
2. Add Compose accessibility and screenshot instrumentation tests.
3. Migrate remaining blocking HTTP calls to coroutine-cancellable asynchronous execution.
4. Build a consented, identity-disjoint visual-correlation benchmark and publish hash-bound calibration results.
5. Add ECDSA signing for evidence manifests if independently verifiable exports are required.
6. Add explicit encrypted HIBP credential storage only if integrated scans need persistent authoritative coverage.
7. Measure provider health and recall longitudinally across regions.
8. Finish localization, RTL, and design-language consolidation.

A future optional user-hosted visual index may broaden candidate acquisition, but it must remain optional, authenticated, resource-bounded, documented, and local-only by default. It is not implemented by the current branch.

## Non-negotiable limitations

- Private or authenticated content cannot be universally discovered.
- Never-indexed and never-archived content cannot be recovered by public search.
- Search providers can omit, delay, challenge, or remove content.
- Image comparison cannot evaluate candidates no acquisition source exposes.
- Historical captures may be unavailable or legally/operationally inaccessible.
- Face-correlation accuracy cannot be inferred from architecture alone.
- HIBP authoritative email coverage is credential-dependent.
- Hashes do not create legal authenticity or third-party attestation.
- CI cannot replace real-device, accessibility, usability, or longitudinal testing.

## Documentation history policy

Old QA reports, milestone notes, status files, device-testing guides, and duplicate roadmaps were intentionally removed. Relevant current conclusions are consolidated here.

Do not reintroduce those files. Update this document with dated, evidence-backed changes and remove superseded statements rather than accumulating contradictory reports.
