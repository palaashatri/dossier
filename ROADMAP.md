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
