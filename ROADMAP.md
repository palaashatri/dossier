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
| 17 | Android UX | Done | main tabs and nested dossier workflow |
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
- Out-of-box visual face-crop comparison through a built-in appearance
  descriptor, while an imported calibrated model remains the stronger optional
  backend. The fallback is not represented as biometric identity proof.
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

## Calibrated face-correlation roadmap

The built-in appearance descriptor remains useful for detecting reuse of the
same photo. Stronger cross-photo correlation requires a separately validated
model pipeline and must remain supporting evidence rather than identity proof.

### Proposed model stack

- OpenCV Zoo YuNet ONNX detector for face boxes and five landmarks.
- OpenCV Zoo SFace ONNX recognizer for aligned face embeddings.
- Pin exact model versions and SHA-256 hashes.
- Ship the corresponding MIT and Apache-2.0 notices and an SBOM entry.
- Keep the existing appearance descriptor as a safe fallback when the model is
  unavailable or the image fails quality gates.

### Required preprocessing

1. Correct EXIF orientation and decode to a bounded bitmap.
2. Detect faces and five landmarks; reject ambiguous multi-face inputs.
3. Apply blur, exposure, size, pose, and occlusion quality gates.
4. Reproduce the SFace reference five-landmark alignment and crop exactly.
5. Use the model's declared channel order, dimensions, and normalization.
6. L2-normalise embeddings and compare with cosine similarity.
7. Record detector/model versions, hashes, quality signals, and threshold-set ID.

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
