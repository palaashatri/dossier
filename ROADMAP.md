# Dossier Roadmap — Code Mapping

This file maps the product roadmap (identity/attack-surface management) to the
actual Android implementation in this repo. The repo has progressed well beyond
the idealized greenfield design; this keeps the two in sync.

## The four questions Dossier must answer

1. **What public information about me exists?** → Scanners (username, public
   profile, public search, public image, reverse media, breach) + PII extraction.
2. **How do those pieces connect?** → `EntityGraphBuilder` + interactive
   `EntityGraphView` (Milestone 8 UI).
3. **How likely is each connection?** → `Finding.confidence`, risk engine, and
   the new `ConfidenceContributor` contracts (Milestone 7, in progress).
4. **How do I reduce my exposure?** → `RemediationProvider` + AI remediation
   advice + plain-text/JSON report export.

## Principles → implementation

- **Everything is evidence.** `Evidence` model (`domain/evidence/Evidence.kt`)
  exists in parallel with the legacy `Finding`; bidirectional adapter keeps both
  interchangeable. Scanners remain the producers.
- **Everything becomes a graph.** `EntityGraph` (`DossierEntity`/`DossierEdge`)
  is the universal fusion output, rendered by `EntityGraphView`.
- **Every conclusion must be explainable.** Findings carry `evidenceSnippet` and
  (for Evidence) `signals`; the graph UI shows per-node evidence on tap.
- **Everything is temporary.** `ScanSession` is in-memory; `purgeSession`
  clears all state. No cloud storage, no accounts, no telemetry.

## Milestone → code

| # | Milestone | Status | Where |
|---|---|---|---|
| 1 | Identity Engine | Done | `domain/model/Models.kt`, `domain/graph/EntityGraphBuilder.kt`, `ui/screens/EntityGraphView.kt` |
| 2 | Scanner Framework | Done | `domain/scanner/ProfileScanner.kt`, `domain/pii/PiiExtractor.kt`, `domain/evidence/ScannerPlugin.kt` |
| 3 | Reverse Image Pipeline | Done | `data/place/ReverseImageLookupService.kt`, `PlaceImageScanner.kt` |
| 4 | Username Correlation | Done | `domain/username/UsernameVariantGenerator.kt` |
| 5 | Public Page Intelligence | Done | `domain/pii/PiiExtractor.kt` |
| 6 | Evidence Correlation Engine | Done | `EntityGraphBuilder` fuses `Evidence` natively (kind→entity + `EvidenceRelationship` seeding) alongside `Finding`; confidence engine consumes `Evidence` |
| 7 | Confidence Engine | Done (core) | `domain/evidence/` — `ConfidenceEngine` + `UsernameSimilarityContributor`, `EmailDomainContributor`, `SharedIdentifierContributor`, `SharedDomainContributor`; per-edge explainable confidence |
| 8 | Identity Graph | Done | `ui/screens/EntityGraphView.kt` (interactive, type-colored, Graph+List a11y) |
| 9 | Exposure Engine | Done | `domain/evidence/ExposureEngine.kt` — 6 sub-scores + Top-10 findings, shown in report "Exposure Breakdown" |
| 10 | Attack Paths | Done | `domain/evidence/AttackPathFinder.kt` — BFS subject→breach, explainable steps, shown in report |
| 11 | Remediation Engine | Done | `domain/remediation/RemediationProvider.kt` — `getStructuredTips()` returns Problem/Evidence/Risk/Fix/Impact, shown in report |
| 12 | AI Layer | Done | `data/ai/AiInsightService.kt`, local Gemma + remote providers |
| 13 | Timeline | Done | `CaseComparisonScreen` (CASES tab) lists saved local cases, single-case snapshot, auto-selects most-recent two |
| 14 | Scan Comparison | Done | `CaseComparisonScreen` renders CaseDiff: added/removed/changed findings, profiles/breaches delta, risk + exposure delta |
| 15 | Plugin SDK | Done (core) | `domain/evidence/ScannerPlugin.kt` interfaces + `PluginRegistry` + `runPlugins` + `SeedEvidencePlugin` example |
| 16 | Performance | Done | Cancellable scan scope + `cancelScan()` + progress streaming; `MemoryGuard` caps retained findings (honest "N omitted" notice); `ScanResumeStore` persists a local resume point surfaced as "Resume last scan" |
| 17 | Android UX | Done | `ui/screens/*`, `MainHubScreen`, bottom-nav tabs |

## Next high-value work

1. ~~Wire `Evidence` as the scanner output type (extend `ProfileScanner` to also
   return `EvidenceCollection`); keep `Finding` via adapter.~~ DONE — `ProfileScanner.toEvidenceCollection` / `scanIdentityEvidence` emit native `EvidenceCollection` (profile + PII + asserted relationships), consumed by the graph/confidence engine; `Finding` adapter retained for backward compat.
2. Add more `ConfidenceContributor`s (same-email, same-domain, shared-avatar)
   and fold them into the `ConfidenceEngine` (completes M7).
3. Add Exposure sub-scores (M9) and a visual attack-path view (M10).
4. Implement Timeline (M13) + Scan Comparison (M14) on a saved-report model.

## Current reverse-image scope

The current release remains phone-first and does not require a Dossier-operated
server. It should continue improving what can be delivered directly in the
Android application:

1. Gather public candidates from multiple independent web and image indexes.
2. Extract original images, `srcset`, OpenGraph images, JSON-LD images, public
   profile avatars, and bounded recursive source-page pivots.
3. Compare candidates locally with exact hashes, perceptual hashes, crop-aware
   variants, and colour histograms.
4. Maintain a bounded local cache/index of public images already encountered by
   the user, with expiry, source provenance, and a user-configurable quota.
5. Report candidate coverage honestly: no result means no match was found in the
   sources inspected, not proof that the image has never appeared online.
6. Keep provider health monitoring, timeouts, circuit breakers, and local
   precision/recall benchmark fixtures in the normal development loop.

A global visual corpus is explicitly outside the phone-only scope. The Android
app must remain useful without one.

## Planned future enhancement — optional self-hosted Visual Index

A broader Visual Index is deferred until after the current local discovery and
matching pipeline is mature. Dossier will not provide or assume access to a
project-operated, employer-operated, or third-party server.

When implemented, the app should present a clear choice:

- **Local-only search:** no server configuration; lower candidate coverage;
  indexing and final matching remain on the phone.
- **Enhanced self-hosted search:** the user explicitly connects Dossier to a
  Visual Index running on hardware or infrastructure they control.

The future server component should be distributed as a versioned Docker image
and a small Docker Compose configuration. Documentation should eventually let a
user deploy it on a desktop, home server, NAS, VPS, or personally authorised
cloud machine with a workflow similar to:

```bash
mkdir dossier-visual-index && cd dossier-visual-index
curl -O https://example.invalid/dossier/docker-compose.yml
# Set a long random API token and storage path in .env
docker compose up -d
```

The URL above is intentionally non-functional until the server project exists.
No Docker image, package name, registry path, or compatibility promise should be
published before there is a built, tested, signed release.

### Planned server responsibilities

- Crawl and revalidate public image/source URLs within configured limits.
- Compute and store SHA-256, pHash, dHash, aHash, crop fingerprints, dimensions,
  and source provenance.
- Build an approximate fingerprint index for candidate retrieval.
- Import optional public datasets or targeted crawl jobs selected by the user.
- Return bounded candidate fingerprints and public URLs to the Android client.
- Expose health, storage, crawl-budget, retention, and deletion controls.
- Support authenticated HTTPS and safe operation behind a reverse proxy or VPN.

### Planned Android integration

- Setup screen for server URL, certificate trust, and API token.
- Explicit connection test and server-capability negotiation.
- Per-scan choice between local-only and enhanced self-hosted search.
- Clear disclosure of what is transmitted before the first enhanced lookup.
- Local final verification of every server candidate.
- Graceful automatic fallback to local-only mode when the server is unavailable.
- One-tap removal of server credentials and deletion of local server-derived data.

### Privacy boundary

The preferred protocol should avoid sending the original image whenever
possible. The phone should compute query fingerprints locally and transmit only
the minimum approximate-search representation needed to retrieve candidates.
This still reveals image-derived identifying metadata and must therefore require
explicit informed consent.

The server must never:

- receive private gallery images by default;
- index authenticated/private pages;
- bypass login gates, CAPTCHAs, robots controls, or access restrictions;
- claim that an absent index result proves an image does not exist online;
- accept public internet traffic with default credentials;
- silently contribute a user's query image or fingerprints to a shared corpus.

### Future acceptance criteria

This enhancement may move from roadmap to implementation only when all of the
following are defined:

1. Stable, documented client/server protocol with version negotiation.
2. Threat model covering fingerprint leakage, server compromise, SSRF, malicious
   candidate URLs, poisoning, denial of service, and deletion requests.
3. Signed multi-architecture Docker images and reproducible builds.
4. Docker Compose deployment with persistent storage, health checks, upgrades,
   backup/restore, authentication, and TLS guidance.
5. Corpus provenance, retention, revalidation, exclusion, and removal policies.
6. Resource limits suitable for a home server as well as a VPS.
7. End-to-end tests proving local-only operation remains the default and fallback.
8. A labelled benchmark demonstrating a material coverage gain over the local
   pipeline without unacceptable false positives.

Until those criteria are met, the Visual Index remains documentation-only and
must not expand the scope of the current Android implementation.

## Identity Graph UI — design decisions (ui-ux-pro-max)

The interactive graph (`ui/screens/EntityGraphView.kt`, M8) was reviewed against
the ui-ux-pro-max skill. Deliberate choices:

- **Style deviation (intentional).** The skill's top pick for a
  privacy/security/intelligence app is *Cyberpunk UI* (neon glow, scanlines,
  terminal fonts). We **rejected** it: the repo's `NeuralTheme` explicitly
  forbids "glow/cyberpunk" (calm, flat, warm-coral on dark), and the skill itself
  rates Cyberpunk accessibility "⚠ Limited (dark+neon)". We kept the app's
  flat dark aesthetic and adopted only the compatible parts.
- **Adopted from the skill:** categorical node colors (one hue per `EntityType`);
  monospace for evidence/labels (Fira Code vibe); relationship **edges at ~60%
  opacity** (skill's network-graph color guidance `#90A4AE 60%`); a **Graph ↔
  List view switcher** providing an **adjacency-list alternative** (skill: network
  graphs are "Very Poor" accessible — must supply a text alternative).
- **Accessibility:** `semantics { contentDescription }` on the canvas; the List
  view is fully selectable/readable; tap targets are full-width rows; color is
  never the only signal (labels + relation text always present).
- **Motion:** no infinite/looping animations; selection is an instant
  color/border change (150–300ms feel via static styling), respecting
  `prefers-reduced-motion` intent. No layout-shifting hover/scale.

See `STATUS.md` for what builds today and `ENHANCEMENTS.md` for the prioritized
sprint list.
