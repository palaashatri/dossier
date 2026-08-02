# Dossier Roadmap — Code Mapping

This file maps the product roadmap to the Android implementation and records the
remaining reliability boundaries honestly.

## Product questions

1. **What public information about me exists?** → username, profile, public web,
   public image, reverse media, breach, and PII scanners.
2. **How do those pieces connect?** → `EntityGraphBuilder` and
   `EntityGraphView`.
3. **How likely is each connection?** → calibrated findings, evidence signals,
   and `ConfidenceEngine` contributors.
4. **How do I reduce exposure?** → `RemediationProvider`, deterministic/optional
   AI analysis, encrypted cases, and evidence-package export.

## Principles

- **Everything is evidence.** Scanner outputs preserve source and confidence.
- **Everything becomes a graph.** `EntityGraph` is the common fusion output.
- **Every conclusion must be explainable.** Verified, review-only, unavailable,
  and not-found states remain distinct.
- **Everything is temporary by default.** Session state is in memory. Saved
  cases are explicit, local, versioned, and encrypted.

## Milestone status

| # | Milestone | Status | Where |
|---|---|---|---|
| 1 | Identity Engine | Done | `domain/model/Models.kt`, graph builder |
| 2 | Scanner Framework | Done | `domain/scanner/ProfileScanner.kt`, scanner plugins |
| 3 | Reverse Image Pipeline | Done (local corpus) | perceptual matching, image candidate search, reverse-media UI |
| 4 | Username Correlation | Done | `UsernameVariantGenerator.kt` |
| 5 | Public Page Intelligence | Done | direct verification and attribution-aware `PiiExtractor` |
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
- Real local whole-image near-duplicate matching using SHA-256, pHash, dHash,
  aHash, colour histograms, and crop variants.
- Out-of-box visual face-crop comparison through a built-in appearance
  descriptor, while an imported calibrated model remains the stronger optional
  backend. The fallback is not represented as biometric identity proof.
- Attribution-aware PII scoring: exact self-supplied identifiers may be high
  confidence; unrelated regex hits remain low-confidence review evidence.
- No fabricated `Demo Subject`. Missing input is an explicit navigation error,
  and cancellation no longer navigates to a valid-looking report.
- HIBP authoritative coverage is separate from public-web exposure. Missing
  credentials, rejected credentials, rate limits, and confirmed no-result states
  are no longer collapsed into a green “clear” result.
- AI output states the engine and whether network analysis was used. Evidence is
  bounded and treated as untrusted data to reduce prompt-injection risk.
- Saved cases use Android Keystore-backed AES-256-GCM envelopes with schema
  versioning, atomic writes, integrity checks, and migration from legacy JSON.
- Report export produces a PDF and a JSON evidence package containing section
  hashes, a manifest hash, timestamps, source data, and analysis provenance.

The implementation now has strong engineering controls, but no honest audit may
assign 10/10 real-world discovery without external measurement. A 10/10 claim
would require near-perfect coverage of content that may be private, deleted,
login-gated, absent from every index, or blocked by a source. It would also
require a sufficiently large labelled corpus, multiple devices and regions,
longitudinal source-health data, and calibrated performance thresholds.

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
