# Dossier — Implementation Truth

This is the sole live implementation/readiness record for Dossier. `AGENTS.md` defines the target; this file describes reality.

## Current snapshot

- **Strict product readiness score:** **48/100**
- **Milestone state:** M0 baseline audit complete; M1 Discovery Fabric v2 in progress
- **Last fully validated main commit before this branch:** `71813b08febc3fa2192ef48bbbc46542be86937c`
- **Current implementation branch:** `feat/product-contract-discovery-v2`
- **Last validated devices:** API 35 x86_64 emulator for Compose smoke tests; no real-device production validation recorded
- **Declarative provider definitions on this branch:** 78 authored
- **Registry-wide live provider validation:** not yet established
- **Production readiness:** not established

Passing compilation or emulator tests does not justify a production-readiness claim.

## Strict 100-point rubric

| Area | Score | Truth |
|---|---:|---|
| Discovery breadth and reliability | **8/15** | Existing multi-provider/search/API discovery works; v2 declarative catalog now exists with 78 definitions, but registry-wide live validation and the 1,000+ target are not met. |
| Recursive orchestration | **3/10** | Existing scanner performs bounded two-hop pivots and resumable input, but there is no central Scan Coordinator, typed event bus, pause/resume execution state, or general frontier. |
| Evidence/provenance | **5/10** | Evidence objects, source URLs, verification state, archive labeling and export integrity exist; stable typed evidence records with complete timestamps/hash/parser/source-reliability fields are not universal. |
| Entity resolution | **3/10** | Confidence contributors exist, but no calibrated resolver with contradiction-aware merge decisions and benchmarked precision/recall exists. |
| Identity graph | **4/8** | Entity graph and relationship confidence are functional and visible; schema is not yet the full typed v2 model and not every subsystem uses it as the sole truth source. |
| Image acquisition/correlation | **5/8** | Public image candidates, exact/perceptual hashing, crops and local comparison exist. Provenance/clustering are incomplete as first-class graph objects. |
| Face-correlation validation | **3/6** | YuNet/SFace model integrity, preprocessing, alignment, quality gates and calibration tooling exist. Representative measured FAR/FRR results and real-device validation do not. |
| Historical evidence | **2/6** | Exact-URL Wayback recovery exists. Full historical extraction, timeline construction and historical graph queries are not implemented. |
| Breach intelligence | **3/5** | HIBP authoritative coverage is separated from public web exposure and uses privacy-preserving range flows where supported. Timeline/case integration and persistent credential workflow remain incomplete. |
| AI analyst | **2/5** | Deterministic fallback and provenance/network disclosure exist. AI is not yet graph-native with structured evidence-ID validation and contradiction-aware output enforcement. |
| UX/UI | **5/8** | Core report/navigation were reworked and six Compose smoke tests pass on API 35 emulator. Streaming scan events, evidence corrections, timeline/image-cluster UX and full accessibility validation remain incomplete. |
| Security/privacy | **3/4** | Encrypted cases, Keystore-backed secrets, restricted WebView, explicit face mode and minimal permissions are implemented. Full security test matrix and export redaction controls remain incomplete. |
| Testing/device validation | **2/5** | Unit/build/calibration CI and Compose emulator smoke tests exist. Samsung/Pixel/lower-memory real-device, battery, process-death, font-scale and accessibility gates are not complete. |
| **Total** | **48/100** | Production contract is substantially broader than the previous architecture audit. |

## M0 — Baseline audit

### Architecture inventory

Current production structure remains primarily:

```text
app/src/main/java/io/dossier/app/
  data/       network services, providers, local models, persistence
  domain/     models, scanner, evidence, graph, risk, remediation
  export/     PDF + JSON evidence export
  ui/         Compose screens/navigation/components/theme
```

Working systems were preserved rather than reorganized for style.

### Discovery inventory before M1

The previous direct profile registry contained 21 platform templates. The scanner also had:

- structured resolvers for selected public services;
- multiple general public search providers;
- public image-index candidate discovery;
- direct source verification;
- retries, bounded budgets and circuit-breaker behavior;
- soft-existence and attribution controls;
- two bounded pivot hops;
- optional linked-personal-site following;
- exact-URL Wayback recovery.

The main architectural problem was that provider metadata and scan planning were not represented by a scalable typed registry.

### Graph inventory

Current graph types are still the earlier model:

```text
Person, Username, Email, Phone, Profile,
Organization, Location, Image, Breach, Website
```

Edges are string relations with optional evidence text. This is functional but below the v2 typed-node/typed-relation/evidence-record contract.

### Image/face inventory

Implemented:

- public image candidate discovery;
- SHA-256, pHash, dHash, aHash and histogram comparison;
- full/centre/square crop variants;
- local YuNet detection and SFace embeddings;
- five-landmark alignment;
- model SHA-256/size pins and atomic installation;
- quality rejection and ambiguous-face handling;
- explicit per-scan strong/basic mode;
- calibration utility with identity-disjoint split support and held-out metrics.

Unvalidated:

- representative production thresholds;
- published FAR/FRR/ROC results on an adequate consented/legal corpus;
- Samsung/Pixel/lower-memory device inference, thermal and battery results.

### Historical/breach inventory

Historical support is currently exact-URL Wayback availability/snapshot recovery with historical labeling. It is not yet a general identity timeline.

HIBP account and password flows are implemented with explicit unavailable/configuration states and are kept distinct from ordinary public search evidence.

### UI inventory

Core investigation output is organized as Overview, Evidence, Connections and Actions. Navigation/onboarding/case/breach/browser/graph semantics were hardened in the prior milestone.

Merged PR #3 added six deterministic Compose instrumentation smoke tests covering consent, identity validation, wizard completion and main utility navigation on an API 35 emulator.

## M1 — Discovery Fabric v2 status

### Implemented on this branch

- Typed `ProviderDefinition` model.
- Provider categories and query capabilities.
- Source-reliability classes.
- Declarative existence/extraction/request-policy rules.
- Safe provider-definition validator.
- Duplicate-ID, template, priority, status-rule and request-policy validation.
- Quick / Standard / Deep / Exhaustive scan-plan model with actual scheduled counts.
- Process-local provider-health tracker with classified outcomes and median latency.
- Deterministic response classifier for present, not found, soft-not-found, authentication-required, automation-challenged, external redirect and unexpected response states.
- 78 authored declarative provider/service definitions across profile, package/code, creative/media, archive, breach and search categories.
- Compatibility adapter: the existing `ProfileScanner` now consumes v2 profile definitions instead of maintaining a second provider list.
- Contract tests for registry validity, scan-plan counts, disabled-provider exclusion, compatibility adaptation, response classification and provider-health metrics.

### Important M1 limitations

M1 is **not complete**.

- 78 definitions is not the 1,000+ long-term target.
- A schema-valid definition is not a live-validated provider.
- The current compatibility scanner can execute only username templates compatible with its existing URL model; subdomain-style templates remain catalogued but are not sprayed by the legacy scanner.
- Scan modes are modeled but not yet exposed as the authoritative runtime scan configuration.
- Provider health is process-local and not yet wired into every live request path.
- Existing scanner concurrency is bounded, but per-provider request spacing from v2 policies is not yet the universal scheduler.
- Generic response classification exists, but mature custom/legacy verification paths have not all been migrated to it.
- Provider maintenance/import tooling is not yet sufficient for safely maintaining 1,000+ definitions.
- Registry-wide sampled live health checks have not been run.

## Existing implemented controls preserved

### Scan lifecycle

- No fabricated fallback identity.
- Missing identity input returns a recovery state.
- Cancellation avoids completed-report navigation.
- Bottom navigation is hidden during the active scan route.
- Resume input is local.
- WebView-backed acquisition tears down on cancellation.

Some remaining synchronous HTTP calls still rely primarily on bounded timeouts rather than fully cancellable async execution.

### Evidence and attribution

- Search candidates are not automatically verified accounts.
- Direct verification and identity evidence are required for stronger attribution.
- Generic PII extraction is confidence-capped unless corroborated.
- Current/historical, verified/review/unavailable/not-found states remain distinct.
- Risk and confidence are separate concepts in UI/reporting.

### Persistence and export

Saved cases use Android Keystore-backed AES-256-GCM, versioned schema, atomic writes and no plaintext fallback for new saves.

Exports provide PDF and JSON evidence packages with section hashes and a manifest hash. These hashes are integrity metadata, not independent attestation.

### Privacy and security

- Reference images remain local for visual processing.
- Strong face correlation requires explicit per-scan choice.
- Evidence WebView defaults to JavaScript/storage/file-access disabled.
- Broad media-library permissions are not required for the system Photo Picker.
- Remote AI remains optional and network use is disclosed.

## Validation gates

Expected core commands:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew connectedUiTestAndroidTest
```

CI also validates the pinned face-calibration Python/OpenCV runtime.

The current branch must not be considered validated until its own CI passes.

## Production blockers

1. Finish M1 with live provider validation, maintenance tooling and runtime scan-mode/policy integration.
2. M2 central Scan Coordinator and typed live event bus.
3. M3 general bounded recursive frontier with explicit pivot admission/rejection.
4. M4 typed Identity Graph v2 and migrations.
5. M5 calibrated contradiction-aware entity resolver benchmark.
6. Representative face benchmark and real-device thresholds.
7. Historical extraction + timeline.
8. Graph-native evidence-ID-validated AI output.
9. User evidence corrections and remediation lifecycle/rescan verification.
10. Real-device Samsung/Pixel/lower-memory validation, accessibility, process death, battery/network profiling and release packaging.

## Non-negotiable limitations

- Dossier cannot guarantee discovery of private, authenticated, never-indexed or never-archived content.
- Provider availability and indexing are externally controlled.
- Search results and visual similarity remain evidence leads until corroborated.
- Historical snapshots may be missing or stale.
- Face architecture alone does not establish real-world accuracy.
- Authoritative HIBP email coverage depends on compatible user-supplied access.
- Emulator CI cannot substitute for real-device testing.

## Documentation policy

Do not add separate roadmap, audit, status or handoff Markdown files. Update this document after meaningful milestones and remove superseded claims instead of accumulating contradictory records.
