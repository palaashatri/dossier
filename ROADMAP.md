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
| 19 | Strong Face Correlation | Implemented; measurement pending | verified YuNet/SFace pack, five-landmark alignment, quality gates, calibration CLI |

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
- Strong cross-photo correlation through a pinned OpenCV YuNet/SFace pipeline,
  while the built-in appearance descriptor remains available for basic
  photo-reuse matching. Reference thresholds are deliberately prevented from
  affecting formal risk until a matching measured calibration is imported.
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

## Strong cross-photo face correlation

The strong local pipeline is now implemented. It is separate from whole-image
reverse search and from the basic appearance descriptor used for detecting reuse
of the same or a near-duplicate photograph.

### Implemented model and inference path

- Official OpenCV Android runtime.
- OpenCV Zoo YuNet 2023mar detector, pinned by exact SHA-256 and byte length.
- OpenCV Zoo SFace 2021dec recognizer, pinned by exact SHA-256 and byte length.
- Explicit user-triggered download; no silent model fetch.
- Temporary-file download, filesystem sync, checksum verification, and atomic
  installation.
- EXIF orientation correction and bounded image decoding.
- YuNet face detection with five landmarks.
- Rejection of ambiguous group photos and low-confidence detections.
- Face-size, image-area, landmark-distance, roll, exposure, and blur gates.
- OpenCV `FaceRecognizerSF.alignCrop` before SFace feature extraction.
- Cosine comparison through the exact OpenCV recognizer API.
- One mutex-protected detector/recognizer runtime per scan service rather than
  reopening the 38 MB recognizer for every profile image.
- Immediate release of image matrices, aligned crops, landmarks, and embeddings.
- Built-in basic appearance matching retained as a non-biometric fallback.

### Consent and user control

- Every scan containing a selfie asks the user to choose strong local
  correlation or basic photo-reuse matching.
- Installation consent and per-scan execution choice are separate.
- Strong mode cannot carry silently into a later scan; the in-memory policy is
  reset on completion, cancellation, invalid input, and installation failure.
- Models, imported calibration, and stored consent can be deleted together.
- No selected image, crop, landmark, or embedding is uploaded.
- Reports continue to describe face similarity as supporting evidence, never
  proof of ownership or identity.

### Threshold policy

Dossier ships a clearly labelled reference policy so the installed pipeline can
return manual-review information:

- `MANUAL_REVIEW` begins at the OpenCV reference cosine operating point.
- `HIGH_SIMILARITY` uses a stricter conservative reference threshold.
- Reference-policy scores are visible but cannot produce formal risk findings.
- Only a measured calibration bound to both exact model hashes and the exact
  pipeline version may affect risk scoring.

This prevents a copied benchmark threshold from being presented as measured
Dossier performance.

### Reproducible calibration tooling

`tools/face_calibration.py` runs the same YuNet → five-landmark alignment →
SFace pipeline over a private consented manifest. It:

1. Verifies the two pinned model hashes and sizes.
2. Applies EXIF-aware decoding and the same image-size and quality gates.
3. Rejects identity overlap between calibration and test splits.
4. Requires minimum positive and negative pair counts.
5. Selects review and high thresholds only on the calibration split.
6. Measures false-match, true-match, and false-non-match rates on untouched test
   identities.
7. Produces bootstrap confidence intervals.
8. Reports demographic-group and device-class slices when supplied.
9. Emits the hash-bound JSON accepted by the Android application.

CI syntax-checks this tool and unit-tests the model pins, calibration contract,
threshold ordering, and per-scan policy.

### Remaining measurement work

The implementation is complete, but a measured 9.5-level claim still requires a
consented corpus that cannot be manufactured from the repository itself:

- Identity-disjoint development, calibration, and locked test identities.
- Same-person pairs across age, pose, lighting, compression, screenshots,
  glasses, facial hair, low-resolution avatars, and different phones.
- Hard negatives involving similar-looking unrelated people, consented relatives,
  and profile-context collisions.
- Strong Indian representation plus balanced demographic and device strata.
- Enough independent negative comparisons to measure the selected high-band
  false-match target with useful confidence.
- Real-device latency, memory, battery, and thermal tests on multiple Android
  devices.
- Review of subgroup disparities before publishing a measured calibration.

Until such a corpus is evaluated, the correct status is:

> **Strong YuNet/SFace implementation available; reference policy only.**
>
> It becomes **measured strong correlation** only after a matching held-out
> calibration file is imported.

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
