# Dossier Roadmap — Code Mapping

This file maps the product roadmap to the Android implementation and records the
remaining reliability boundaries honestly.

## Product questions

1. **What public information about me exists?** → username, profile, public web,
   public image, reverse media, breach, PII, and historical archive scanners.
2. **How do those pieces connect?** → `EntityGraphBuilder` and
   `EntityGraphView`.
3. **How likely is each connection?** → calibrated findings, evidence signals,
   and `ConfidenceEngine` contributors.
4. **How do I reduce exposure?** → `RemediationProvider`, deterministic/optional
   AI analysis, encrypted cases, and evidence-package export.

## Principles

- **Everything is evidence.** Scanner outputs preserve source and confidence.
- **Everything becomes a graph.** `EntityGraph` is the common fusion output.
- **Every conclusion must be explainable.** Verified, historical, review-only,
  unavailable, and not-found states remain distinct.
- **Everything is temporary by default.** Session state is in memory. Saved
  cases are explicit, local, versioned, and encrypted.

## Milestone status

| # | Milestone | Status | Where |
|---|---|---|---|
| 1 | Identity Engine | Done | `domain/model/Models.kt`, graph builder |
| 2 | Scanner Framework | Done | `domain/scanner/ProfileScanner.kt`, scanner plugins |
| 3 | Reverse Image Pipeline | Done (local corpus) | perceptual matching, image candidate search, reverse-media UI |
| 4 | Username Correlation | Done | `UsernameVariantGenerator.kt` |
| 5 | Public Page Intelligence | Done | direct verification, attribution-aware PII, Wayback recovery |
| 6 | Evidence Correlation | Done | native Evidence output + graph fusion |
| 7 | Confidence Engine | Done (core) | contributors and calibrated ceilings |
| 8 | Identity Graph | Done | interactive graph and accessible list |
| 9 | Exposure Engine | Done | exposure sub-scores and report UI |
| 10 | Attack Paths | Done | explainable relationship paths |
| 11 | Remediation Engine | Done | structured and global remediation |
| 12 | AI Layer | Done (optional) | deterministic fallback, local Gemma, remote providers, explicit provenance |
| 13 | Timeline | Done | encrypted saved cases |
| 14 | Scan Comparison | Done | case diff UI |
| 15 | Plugin SDK | Done (core) | plugin contracts and registry |
| 16 | Performance/Lifecycle | Done (core) | bounded work, cancellation routing, resume, memory guard |
| 17 | Android UX | Done (core) | task-oriented navigation, responsive setup, report views, accessible semantics |
| 18 | Evidence Export | Done (core) | PDF plus machine-readable JSON hash manifest |

## Post-hardening audit status

The August 2026 reliability pass fixed the major controllable defects from the
original functionality audit:

- Six general search surfaces plus structured profile APIs, direct page
  verification, caches, circuit breakers, retries, and scheduled canaries.
- Exact-URL Wayback recovery for deleted, replaced, or stale indexed pages.
  Archive findings are capped and labelled historical; they never prove a
  profile is currently active.
- Real local whole-image near-duplicate matching using SHA-256, pHash, dHash,
  aHash, colour histograms, and crop variants.
- Measured-ready YuNet/SFace cross-photo face correlation with pinned models,
  five-landmark alignment, quality gates, explicit per-scan consent, and a
  reproducible identity-disjoint calibration tool. Reference thresholds remain
  manual-review evidence until a matching measured calibration is imported.
- Attribution-aware PII scoring: exact self-supplied identifiers may be high
  confidence; unrelated regex hits remain low-confidence review evidence.
- No fabricated `Demo Subject`. Missing input is an explicit navigation error,
  and cancellation no longer navigates to a valid-looking report.
- HIBP authoritative coverage is separate from public-web exposure. Email
  lookups use HIBP's six-character SHA-1 k-anonymity range endpoint when the
  configured subscription supports it; the complete address is not sent and
  Dossier does not silently fall back to a direct-address lookup.
- AI output states the engine and whether network analysis was used. Evidence is
  bounded and treated as untrusted data to reduce prompt-injection risk.
- Saved cases use Android Keystore-backed AES-256-GCM envelopes with schema
  versioning, atomic writes, integrity checks, and migration from legacy JSON.
- Report export produces a PDF and a JSON evidence package containing section
  hashes, a manifest hash, timestamps, source data, and analysis provenance.

The implementation now has strong engineering controls, but no honest audit may
assign 10/10 real-world discovery without external measurement. A 10/10 claim
would require near-perfect coverage of content that may be private, never
archived, login-gated, absent from every index, or blocked by a source. It would
also require a sufficiently large labelled corpus, multiple devices and regions,
longitudinal source-health data, and calibrated performance thresholds.

## UI/UX hardening status

A source-level UI/UX audit was completed across onboarding, top-level navigation,
identity setup, scanning, reports, saved cases, breach checks, evidence browsing,
relationship graphs, shared controls, permissions, motion, and export language.

Implemented improvements:

- Consent now distinguishes public-network discovery, optional HIBP/remote AI,
  local visual processing, resumable input, encrypted saved cases, and exports.
- Consent is removed from the navigation back stack after acceptance.
- The bottom navigation is hidden during an active scan so changing tabs cannot
  dispose and accidentally restart the scan route.
- Identity setup uses saveable state, narrow-screen-safe actions, multiline
  inputs, email/URL validation, removable reference photos, and accessible step
  semantics.
- Deep Research is presented as an optional bounded public-link expansion with
  explicit latency and network-request implications.
- Breach input preserves exact password whitespace, reports invalid emails,
  clears plaintext password state before network work, and supports cancellation.
- Saved cases have explicit older/newer roles, correct risk-delta semantics, and
  confirmed permanent deletion.
- The report is divided into Overview, Evidence, Connections, and Actions. Risk
  and attribution confidence are visually and verbally separate; empty results
  no longer imply a clean bill of privacy.
- The relationship graph is normalised into positive coordinates, scrollable in
  two directions, and accompanied by a complete text-list alternative.
- Evidence browsing defaults to a restricted WebView: HTTP(S) only, JavaScript
  and persistent page storage off, no file/content access, no mixed content,
  history-aware Back, and deterministic destruction.
- Light/dark semantic colours have stronger contrast and filled accent controls
  use an explicit foreground colour.
- Decorative route transitions respect Android's disabled-animation setting and
  no longer block navigation for an extended interval.
- Broad media-library permissions were removed in favour of Android's system
  Photo Picker. Camera cancellation cleans up temporary files.
- A branded adaptive launcher icon replaces the generic Android application icon.
- PDF/plain-text exports use calm privacy-audit terminology rather than
  theatrical classifications such as “confidential threat dossier.”

Remaining validation work before claiming a 9.5–10/10 UI/UX score:

1. Capture rendered screenshots on the target Samsung S25 and at least one small
   and one large Android viewport.
2. Test 100%, 130%, 160%, and 200% font scaling and display-size settings.
3. Run TalkBack traversal, switch access, keyboard navigation, and Android
   automated accessibility checks on every primary flow.
4. Measure contrast against rendered backgrounds, including disabled states.
5. Test process death, rotation, low-memory recreation, external-browser return,
   camera cancellation, and system Photo Picker fallback behavior.
6. Add Compose instrumentation/screenshot regression tests for onboarding,
   identity setup, scan consent, report tabs, breach errors, cases, and graph
   list mode.
7. Perform real-user usability testing for terminology, evidence interpretation,
   and remediation comprehension.
8. Consolidate the remaining legacy engine-management and reverse-media screens
   into the calmer design language and remove obsolete/orphaned UI code.
9. Move remaining user-visible strings into Android resources and add
   localization and RTL validation.

The current source architecture is substantially stronger, but source inspection
and a successful APK build are not substitutes for rendered-device visual QA.

## Historical archive scope

The shipping archive path is intentionally bounded:

1. Query the Internet Archive Wayback Availability API for one exact URL only.
2. Fetch only the closest accessible successful capture.
3. Enforce HTTPS, host validation, timeouts, content-type checks, and a 2 MB cap.
4. Require the archived page itself to expose an identity signal; stale search
   snippets do not establish attribution.
5. Cap confidence below current-page evidence and label the capture date,
   provider, original URL, and historical-only status.
6. Never submit pages to an archive automatically.

A future fallback may use Common Crawl's exact-URL index when Wayback has no
capture. Archive.today and similar services must not become automated
requirements without stable documented APIs and acceptable operating terms.

## Calibrated face-correlation implementation

The strong local pipeline now uses the official OpenCV Zoo YuNet detector and
SFace recognizer through Android's OpenCV bindings.

Shipping implementation:

- checksum- and size-pinned YuNet/SFace model pack;
- explicit install consent plus a separate per-scan execution choice;
- OpenCV decode, EXIF correction, bounded resize, YuNet detection, ambiguity
  rejection, quality gates, five-landmark `alignCrop`, SFace embeddings, and
  cosine scoring;
- full off-thread hash verification before the first strong inference in each
  process;
- reference thresholds that remain manual-review only;
- imported measured calibration bound to both hashes and the pipeline version;
- minimum held-out corpus sizes and FMR/TMR checks before measured scores may
  influence formal findings;
- transient matrices, crops, landmarks, and embeddings released after use;
- a pinned Python/OpenCV calibration environment validated in CI.

The remaining gap is empirical measurement, not missing application code.

### Calibration and benchmark requirements

- Split data by identity into development, calibration, and untouched test sets.
- Include same-person pairs across age, pose, lighting, compression, screenshots,
  glasses, facial hair, low-resolution avatars, and different phones.
- Include hard negative pairs with similar appearance, relatives where consented,
  and same-name/profile-context collisions.
- Evaluate standard public benchmarks through download-only harnesses without
  redistributing restricted datasets.
- Add a consented mobile dataset with strong Indian representation and balanced
  demographic/device strata.
- Report ROC/DET curves, false-match rate, false-non-match rate, equal-error
  rate, true-accept rate at fixed false-accept rates, subgroup results, and
  bootstrap confidence intervals.
- Establish separate `NO_SUPPORT`, `MANUAL_REVIEW`, and `HIGH_SIMILARITY` bands.
  No threshold may produce an automatic ownership conclusion.
- A 9.5-level claim requires a locked held-out benchmark large enough to measure
  the chosen false-match target, real-device performance and thermal tests, and
  independently reproducible results.

### Consent and retention controls

- Explicit opt-in before face comparison.
- On-device inference by default; never upload face crops or embeddings silently.
- Delete transient crops and embeddings after the scan unless the user explicitly
  saves a case that includes them.
- Never contribute query embeddings to a shared or self-hosted visual index.
- Provide model/version disclosure, limitations, deletion controls, and a clear
  manual-review requirement in the UI and exports.

## Current reverse-image scope

The shipping path is phone-first and does not require a Dossier-operated server:

1. Gather public candidates from multiple web and image indexes.
2. Extract originals, thumbnails, profile avatars, and source-page images.
3. Compare candidates locally using exact and perceptual fingerprints.
4. Preserve provider provenance and bounded resource use.
5. Report “not found in inspected candidates,” never “never appeared online.”

A global visual corpus remains outside the phone-only scope.

## Planned future enhancement — optional self-hosted Visual Index

A broader Visual Index is deferred. Dossier will not assume project-operated,
employer-operated, or mandatory third-party infrastructure.

Future modes:

- **Local-only:** no server configuration; lower candidate coverage.
- **Enhanced self-hosted:** the user connects Dossier to a Visual Index running
  on a desktop, home server, NAS, VPS, or personally authorised cloud machine.

When implemented, the server should be delivered as signed multi-architecture
Docker images and a small Docker Compose deployment. No image name, registry,
or installation command should be advertised until a real tested release exists.

### Planned server responsibilities

- Crawl and revalidate public image/source URLs within configured limits.
- Store exact and perceptual fingerprints plus source provenance.
- Retrieve approximate candidate fingerprints for local verification.
- Import only datasets and crawl jobs explicitly enabled by the operator.
- Expose health, storage, retention, deletion, and crawl-budget controls.
- Require authenticated HTTPS or operation behind a trusted VPN/reverse proxy.

### Planned Android integration

- Server URL, certificate trust, and API-token setup.
- Capability/version negotiation and explicit connection test.
- Per-scan local-only or enhanced-self-hosted choice.
- Clear disclosure of transmitted image-derived fingerprints.
- Local verification of every server-returned candidate.
- Automatic local-only fallback and one-tap credential removal.

### Acceptance criteria

The enhancement remains documentation-only until there is:

1. A stable versioned client/server protocol.
2. A threat model covering fingerprint leakage, SSRF, malicious URLs, poisoning,
   denial of service, server compromise, and deletion requests.
3. Signed reproducible Docker images.
4. Backup, restore, upgrade, health-check, TLS, and authentication guidance.
5. Corpus provenance, retention, exclusion, revalidation, and removal policies.
6. Resource limits suitable for home machines and VPS deployments.
7. End-to-end tests preserving local-only default and fallback behaviour.
8. A labelled benchmark proving meaningful recall gain without unacceptable
   false positives.
