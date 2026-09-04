# Dossier — Implementation Truth

This is the authoritative current-state record for Dossier.

`AGENTS.md` defines the target product. This file records what actually exists, what has been validated, what is reusable, what is misleading under the new mission, and what must change next.

## 1. Current reset state

- **Current branch:** `feat/product-contract-discovery-v2`
- **Open pull request:** PR #4
- **Audited implementation baseline before the documentation reset:** `22b158fd45bcbe58c1a946ed0864ff8a2b3a3b69`
- **Product-contract reset commit:** `cf0f11d67974ce881513ff36130bbe2d9a7aa3d7`
- **Audit/reset date:** 2026-09-04
- **Previous strict readiness score:** **83/100 — RETIRED as the primary product metric**
- **Current mission-readiness score:** **UNSCORED / NOT ESTABLISHED**
- **Reason:** Dossier does not yet have a representative end-to-end exposure-reconstruction benchmark, so publishing another flattering overall score would be misleading.

The previous implementation accumulated substantial engineering hardening, provenance, persistence, graph, image, face, recovery, remediation, and UI work. That work is not discarded. The failure was primarily a product-contract and discovery-architecture mismatch: Dossier was optimized heavily for bounded, conservative provider checks and implementation completeness instead of maximizing measurable exact exposure recovery from a minimal seed.

The new acceptance question is:

> Starting from only one chosen authorized seed, how much correct exact exposure can Dossier independently recover, how quickly, from which accessible sources, and with how few false positives?

Until that question is measured against a real synthetic/consented corpus, mission readiness is not numerically established.

## 2. Repository/PR scale at reset

At the audited PR head before this documentation reset:

- PR #4 was open and mergeable.
- The branch was **343 commits** ahead through the PR history.
- The PR touched **277 files**.
- The diff contained roughly **75k additions** and **2.3k deletions**.

This size is one reason the next phase must prefer targeted preservation and simplification over another broad feature-accumulation pass.

## 3. CI truth at the audited head

For audited head `22b158fd45bcbe58c1a946ed0864ff8a2b3a3b69`:

- **Android CI:** PASS
- **Compose smoke tests:** PASS
- **Provider Registry Audit:** FAIL

The provider audit failure is now known precisely. `tools/provider_registry_audit.py --json` reported:

```text
WhatsMyName license SHA-256 does not match the pinned digest
```

The pinned WhatsMyName data file itself still matched its expected hash and size, and the audit still parsed:

- 716 source records;
- 644 executable HTTPS username rules;
- 72 excluded rows;
- 78 authored provider definitions;
- 0 conversion errors;
- 0 primary-catalog ID collisions.

The CI failure therefore is not a generic unknown workflow failure. The bundled license content hash changed relative to the audit's pinned expected digest and must be reviewed/fixed deliberately rather than bypassed.

Do not describe the branch as green until this is corrected and CI is rerun on the resulting head.

## 4. Older validation evidence

The previous `TRUTH.md` recorded extensive validation around earlier implementation commits, especially `45520a1` and an earlier full Android artifact gate around `8cbef92`.

That evidence included large JVM suites, APK assembly, lint, emulator instrumentation, WorkManager pause/resume, encrypted result restoration, graph/evidence reconciliation, case persistence, provenance, face checkpointing, and other regressions.

Those results remain useful evidence about the underlying foundations, but they must not be represented as current-head production acceptance after the product reset.

The reset explicitly separates:

```text
historically validated infrastructure
from
new mission-level discovery effectiveness
```

The latter is currently unmeasured.

## 5. What is worth preserving

The existing codebase is a decent base. A large rewrite from zero is not justified.

The following areas are materially useful and should be preserved/adapted unless implementation work finds a concrete defect.

### 5.1 Encrypted cases and bounded persistence

The project already has substantial encrypted case/result persistence, bounded collection shapes, request ownership concepts, deletion controls, and migration behavior.

Relevant existing areas include:

- `CaseStore`
- `DossierCase`
- background result stores
- scan lifecycle stores
- encrypted checkpoint patterns
- case comparison and remediation state

These foundations are directly useful for local exact-value exposure storage and private benchmark data, although the latter still requires a deliberately separate local-only benchmark contract.

**Status:** reusable foundation, not mission-complete.

### 5.2 Evidence and provenance

Dossier already treats findings as evidence rather than automatic identity truth.

Existing useful behavior includes:

- stable evidence IDs;
- provider/source metadata;
- retrieval/observation timestamps;
- verification states;
- reliability fields;
- hashes/parser versions in several paths;
- explicit relationship evidence IDs;
- contradiction support;
- user corrections;
- exact-only provenance hardening in several graph paths.

This is a strong fit for the new Exposure Ledger.

**Status:** strong reusable foundation; needs canonical consolidation and exact-value fact modeling.

### 5.3 Graph and relationship infrastructure

The typed graph, graph/evidence reconciliation, relationship normalization, graph export, and provenance-aware relationships are worth keeping.

The reset does **not** require discarding the graph. It requires making the Exposure Ledger/canonical evidence assertions the clear source material for graph projections and reducing remaining parallel truths.

**Status:** reusable with consolidation work.

### 5.4 Background execution and recovery

The project has nontrivial WorkManager ownership, scan lifecycle, pause/resume, checkpoint, result recovery, and process-restoration work.

That is valuable for deep scans and a persisted recursive frontier.

The current coordinator does not yet own the entire discovery system cleanly, and the frontier is not yet the general recursive exposure frontier required by Discovery Engine v3.

**Status:** reusable execution substrate; orchestration model needs refactoring.

### 5.5 Existing face/image work

The project already contains:

- local image hashing;
- exact/perceptual duplicate clustering;
- image candidate provenance;
- YuNet/SFace local face detection/correlation infrastructure;
- face quality gates;
- bounded comparison provenance;
- OCR/image-analysis dependencies;
- reverse-image candidate and visual-matching code;
- EXIF parsing.

This means the new Photo seed flow does not start from scratch.

What is missing is the product-level orchestration:

```text
photo
 → metadata
 → OCR
 → reverse-image providers
 → source pages
 → face candidate images
 → location candidates
 → recursive identity/exposure frontier
```

**Status:** substantial reusable base; currently fragmented rather than one photo-investigation pipeline.

### 5.6 Archive and breach scaffolding

The code already contains Wayback/history support and HIBP/breach-check scaffolding.

These remain relevant but are not sufficient exposure reconstruction on their own.

**Status:** reusable evidence families.

### 5.7 Remediation and exports

User corrections, remediation tracking, differential case comparison, and share-safe export/redaction concepts remain useful.

They become more valuable once the discovery layer can actually recover the exact facts the user wants to remediate.

**Status:** preserve; lower priority than discovery recall.

### 5.8 Compose UI shell

The current Compose application and many components/screens are usable implementation material.

The launch/navigation contract, however, must change. The app should no longer make the user enter through a complex multi-screen identity/scanner workflow.

**Status:** preserve components; redesign entry flow and results hierarchy.

## 6. Core product mismatch discovered in the audit

The previous Dossier implementation is sophisticated as a conservative evidence-oriented OSINT/privacy application, but it does not yet behave like a strong personal exposure reconstruction engine.

The most important mismatch is:

```text
old optimization:
prove provider checks conservatively and avoid false attribution

new required optimization:
find as much correct exact exposure as possible, quickly,
while preserving conservative attribution and provenance
```

Dossier needs both halves.

## 7. Discovery bottlenecks verified

### 7.1 WhatsMyName fixed-batch execution

`WhatsMyNameUsernamePlugin.scan()` currently constructs operations and executes them using fixed groups equivalent to:

```text
chunked(MAX_CONCURRENCY)
  → async each operation
  → awaitAll for the entire chunk
  → only then start the next chunk
```

Current constants include:

- maximum 3 handles;
- Quick: first 50 sites;
- Standard: first 200;
- Deep: first 500;
- Exhaustive: all eligible rows;
- `MAX_CONCURRENCY = 6`;
- maximum planned operations = `3 × 644 = 1932`.

This architecture creates unnecessary tail latency because one straggler can delay starting work from the next batch.

The provider scheduler also serializes work by scheduling key/domain and enforces request spacing, which is correct for politeness but means fixed six-operation batches can have substantially less than six effective active hosts depending on operation ordering.

**Truth:** slow architecture; replace with rolling workers, not merely a larger constant.

### 7.2 Plugin families execute serially

`runPlugins()` currently loops through registered plugins sequentially.

The default family includes Reddit activity, WhatsMyName, Wayback, and multiple import plugins.

That means a slow username pass can delay logically independent archive or evidence-family work.

**Truth:** independent families are not yet parallelized under one bounded shared coordinator.

### 7.3 Public search is intentionally shallow

`PublicSearchDiscoveryService` currently uses multiple search indexes and direct verification, but the architecture is hard-capped.

Current limits include approximately:

- default queries: 24;
- deep queries: 40;
- parallel search queries: 3;
- direct verifications: 28;
- pre-verification candidates: 58;
- final results: 34.

The query design heavily emphasizes conventional profile sites and a bounded set of forums/search combinations.

This was appropriate for a mobile bounded audit, but it is not enough for the new target of recursive exact exposure reconstruction.

**Truth:** useful component, wrong stopping model.

### 7.4 No persistent general exposure frontier

There is pivot/frontier code, but the product does not yet have the general typed persistent frontier defined in the new `AGENTS.md` where each verified email, phone, alias, document, profile, location, photo/source page, etc. can become a new prioritized search seed.

**Truth:** partial precursor exists; required general frontier does not.

### 7.5 Real-world recall is not measured

The existing `DiscoveryPrecisionRecallTest` verifies metric arithmetic using constructed observations. It does not execute the discovery engine against a known multi-hop identity/exposure corpus.

Therefore:

- actual Recall@known-exposure is unknown;
- actual precision on a representative corpus is unknown;
- time to 50%/80% recall is unknown;
- exact-value recovery rate is unknown;
- source-yield ranking is not grounded in a mission benchmark.

**Truth:** this is the largest measurement hole in the project.

## 8. Provider/source catalogue truth

The project currently contains:

- 78 authored provider/service definitions;
- a pinned WhatsMyName source catalogue with 716 rows and 644 executable HTTPS username rules after policy filtering;
- a much broader `PublicSourceCatalog` describing many external tools/services.

A large fraction of the broader catalogue is `ImportOnly`, `ManualOnly`, `ApiKeyRequired`, degraded, retired, or otherwise not autonomous production discovery.

Examples in the existing catalogue include external/import-only tool families for username search, social exports, corporate tooling, breach tooling, infrastructure tooling, graph tooling, and other OSINT ecosystems.

The new contract requires counts for catalogued, executable, automated, live-reachable, live-validated, import-only, manual-only, and retired sources to remain distinct.

**Truth:** catalogue breadth is not equivalent to autonomous coverage.

## 9. Exact-value exposure support truth

The existing application can store and display many evidence values, but it does not yet have a dedicated first-class Exposure Ledger whose mission is to preserve the exact exposed value plus source, provenance, discovery path, historical/current state, and remediation state.

The current product often frames evidence around profiles/findings rather than around the question:

> What exact facts about me are exposed?

**Truth:** evidence foundations exist; the exact-value Exposure Ledger product object does not yet exist as defined in the reset contract.

## 10. Universal launch UX truth

The desired launch contract is now:

```text
one universal text field
+ camera action
+ photo/file action
```

The field should accept at least name, username, phone, email, and URL; the app should classify the seed and begin useful work without forcing a large identity form.

The existing application has multiple identity/scan/configuration screens and a broader hub/navigation structure.

**Truth:** current Compose components can be reused, but the universal launch flow is not implemented yet.

## 11. Photo seed truth

The desired Photo seed should run these families in parallel:

```text
local metadata / EXIF / XMP / IPTC
OCR
face detection/correlation
image hashing and duplicate detection
reverse-image search
source-page extraction
photo location reconstruction
recursive identity/exposure pivots
```

### What already exists

- EXIF parsing exists.
- Local face/image infrastructure exists.
- OCR/image-labeling dependencies exist.
- Reverse-image candidate search/visual matching exists.
- Image provenance and duplicate/repost concepts exist.

### What does not yet exist as one production flow

- photo as the universal initial seed;
- one orchestrated fan-out pipeline;
- reviewed multi-provider reverse-image adapter surface matching the new contract;
- metadata-stripped derivative upload policy integrated end-to-end;
- ranked evidence-backed photo location candidates;
- source-page location extraction feeding a location evidence model;
- reverse-image results automatically becoming recursive source-page/identity pivots through one canonical frontier.

**Truth:** strong base, missing integration and mission-level product flow.

## 12. Photo location reconstruction truth

The desired location output classes are now:

```text
EXACT_METADATA
CORROBORATED_LOCATION
LIKELY_LOCATION
VISUAL_GUESS
CONFLICTING
```

The current code has pieces for EXIF/geography/media intelligence, but no audited production subsystem currently establishes the complete evidence-fusion contract above.

**Truth:** partial components only; no complete photo geolocation product claim.

## 13. Reverse-image/browser integration truth

The code already includes Yandex-oriented reverse-image candidate work and manual/external catalogue references for services such as Google Lens.

The reset target is a provider-adapter model where documented APIs are preferred and visible browser-backed integrations are permitted when stable/appropriate.

Playwright may be useful for CI/browser adapter regression testing or external tooling, but Playwright-over-ADB is not the planned Android runtime architecture.

**Truth:** reverse-image support exists, but not yet at the desired multi-provider, recursively integrated level.

## 14. PII and repository hygiene audit

The reset audit found concrete hygiene issues.

### 14.1 Hard-coded device/browser fingerprint

At least the current search code contains a hard-coded browser-style User-Agent including:

```text
SM-S931B
```

Related source locations identified in the repository include search, WebView/reverse-image paths.

This is inappropriate as product network identity and can reveal developer/device assumptions.

The new contract requires a generic Dossier-owned UA or the normal platform WebView UA rather than hard-coded contributor hardware/browser impersonation.

**Status:** must remove.

### 14.2 Repository-owner URL in runtime User-Agent strings

Several networking classes embed the repository URL in a runtime User-Agent string.

This is not sensitive PII at the level of a phone/address, but it unnecessarily couples runtime network identity to a contributor/repository owner and should be replaced with generic product identity.

**Status:** must remove from runtime UA values.

### 14.3 Committed editor/agent state

The branch currently contains committed directories including:

```text
.idea/
.serena/
.vscode/
```

These are not product code and should be removed unless a specific shared configuration is demonstrably required. The default cleanup decision is removal plus `.gitignore` coverage.

**Status:** cleanup candidate.

### 14.4 Empty/meaningless maintenance marker

`tools/provider_registry_audit_fixed_marker.txt` contains only a sentence indicating that a guard was reviewed.

It provides no runtime or meaningful maintenance function.

**Status:** delete.

### 14.5 Duplicate WhatsMyName maintenance verification

`tools/verify_whatsmyname_catalog.ps1` duplicates a meaningful subset of the Python provider registry audit: pinned hashes, size, source counts, and executable filtering.

Unless there is a required Windows-only workflow depending on it, one maintained implementation should be enough.

**Status:** likely delete after confirming no workflow references it.

### 14.6 Overlapping `PublicSourceCatalogTest` classes

There are two test classes with the same conceptual name in different packages that both validate source-catalog state/integration semantics.

They are not byte-for-byte duplicates, but coverage overlaps and should be consolidated if one focused contract test can retain the meaningful assertions.

**Status:** review/merge, not blind deletion.

## 15. README truth

The existing README still describes the previous 83/100 status and older product hierarchy.

It also embeds the old screenshot walkthrough and explains provider-budget behavior in terms that are no longer the primary mission contract.

**Status:** README is now stale relative to the reset and must be rewritten after the first cleanup/discovery-v3 tranche so it describes implemented reality rather than the target.

## 16. Safety/authorization boundary

The new mission does not require weakening source authorization boundaries.

Dossier may aggressively search and correlate information the user is authorized to investigate through accessible public pages, public documents, archives, reviewed APIs, user-authorized local evidence, and supported providers.

Dossier must not implement credential theft, access-control bypass, CAPTCHA/challenge bypass, hidden tracking, or acquisition mechanisms for stolen/private databases.

This boundary does not require masking the user's own exact exposed values in the local self-audit ledger.

## 17. New primary metrics

The old score is retired because it rewarded many useful engineering properties without answering whether Dossier actually finds enough exposure.

The next benchmark must report at least:

```text
known exposed facts N
exact facts rediscovered X
Recall@known-exposure = X / N
precision
false-positive rate
unresolved candidates
time to first useful finding
time to first verified identity anchor
time to first high-value identifier
time to 50% recall
time to 80% recall
total scan duration
provider/source failure rate
useful findings per request
new useful pivots per verified finding
```

For photo benchmarks add:

```text
EXIF location recovery accuracy
OCR location clue recovery
reverse-image source recovery
face-candidate precision/recall on consented/synthetic data
location candidate ranking accuracy
exact/near-duplicate image retrieval
```

## 18. Private self-audit benchmark contract

A real user's known exposure dataset must never be committed to Git or required by CI.

The private benchmark should live in encrypted/gitignored local storage and may contain exact values needed for local scoring.

The repository and CI use synthetic identities only.

Local benchmark reporting should support aggregate output such as:

```text
phones recovered: 2/2
emails recovered: 3/4
address recovered: yes
postal code recovered: yes
social accounts recovered: 7/8
```

The exact values remain visible only inside the local private evidence view when the user chooses to inspect them.

## 19. Reset implementation priorities

### P0 — Hygiene, CI, and truthful measurement

Not yet completed under the reset.

Required:

- remove hard-coded device/contributor runtime identifiers;
- remove editor/agent junk;
- delete meaningless marker files;
- review redundant maintenance scripts;
- consolidate overlapping tests where safe;
- fix the WhatsMyName license-hash audit failure correctly;
- establish synthetic end-to-end discovery corpus structure;
- establish local private benchmark storage contract;
- rewrite README after the implementation starts matching the new contract.

### P1 — Universal launch and Discovery Engine v3 scheduler

Not implemented yet as the new contract.

Required:

- one universal text/photo entry flow;
- seed type classification;
- rolling scheduler;
- concurrent independent evidence families;
- shared global/per-domain limits;
- health/yield/latency priority;
- immediate result streaming;
- persisted typed frontier.

### P2 — Exact-value Exposure Ledger and recursive search

Not implemented yet as the new contract.

Required:

- canonical fact types;
- exact + normalized values;
- source classification;
- discovery path;
- evidence links;
- first/last observed;
- historical/current state;
- remediation state;
- recursive pivots from newly verified facts;
- stronger documents/directories/archives/public-web extraction.

### P3 — Photo investigation and location reconstruction

Partially supported by existing components, not integrated.

Required:

- Photo universal seed;
- parallel metadata/OCR/face/reverse-image/location pipelines;
- metadata-stripped remote upload derivatives;
- multi-provider reverse-image adapters;
- source-page recursive parsing;
- evidence-ranked candidate locations;
- map/location UX;
- benchmarked location confidence tiers.

### P4 — Canonical graph/evidence consolidation

Partially implemented.

Required:

- Exposure Ledger and canonical evidence assertions become the authoritative facts feeding graph projection;
- reduce remaining parallel graph/scanner representations;
- preserve contradiction/user-correction semantics;
- keep export/remediation behavior compatible with exact local facts.

### P5 — Product hardening

Still open:

- physical-device testing;
- accessibility breadth;
- performance/memory/battery/thermal work;
- large-case scaling;
- release/security review;
- representative calibration where probabilistic components remain.

## 20. What is deliberately lower priority now

Until the new benchmark and discovery engine exist, do not spend major effort on:

- adding hundreds more username sites merely to raise provider count;
- decorative UI polish unrelated to the universal search/results flow;
- broad AI features;
- additional graph export formats;
- new face models without a demonstrated benchmark need;
- marketing-style capability catalog expansion;
- test-count inflation.

Keep existing working capabilities healthy, but direct new work toward measured discovery utility.

## 21. Current mission assessment by subsystem

These are qualitative reset states, not a disguised numeric score.

| Area | Current reset assessment | Why |
|---|---|---|
| Universal one-box launch | **Not implemented** | Existing identity/scan flow is more complex than the new contract. |
| Public web discovery | **Partial** | Multiple search engines and direct verification exist, but hard caps and shallow stopping dominate. |
| Username discovery | **Implemented but slow/secondary** | Large pinned catalogue works as an existence surface; fixed-batch execution and provider-count focus are wrong priorities. |
| Recursive exposure frontier | **Partial precursor** | Pivot/frontier infrastructure exists, but not the required general typed persisted exposure frontier. |
| Exact-value extraction | **Partial** | Evidence values exist; no complete first-class exact Exposure Ledger/extractor mission. |
| Exposure Ledger | **Not implemented as canonical product object** | Must be introduced from existing evidence foundations. |
| Evidence/provenance | **Strong base** | Significant existing hardening and IDs/provenance can be reused. |
| Entity resolution | **Partial/uncalibrated** | Conservative logic exists; representative mission benchmark missing. |
| Graph | **Strong base but not sole truth** | Typed graph and reconciliation exist; canonical consolidation remains. |
| Documents/directories | **Weak relative to target** | Not yet a primary discovery family. |
| Archives/history | **Partial useful base** | Wayback/history exists, needs deeper frontier integration. |
| Breach awareness | **Partial useful base** | HIBP/scaffolding exists; exact exposure reconstruction remains source-dependent. |
| Local case security | **Strong base** | Encrypted/bounded persistence work is reusable. |
| Photo metadata | **Partial base** | EXIF support exists; universal Photo flow does not. |
| OCR/image analysis | **Partial base** | Dependencies/code exist; not yet central recursive discovery. |
| Face correlation | **Substantial base, calibration incomplete** | Local pipeline exists; candidate acquisition and mission benchmark need work. |
| Reverse image | **Partial** | Candidate/matching work exists; multi-provider recursively integrated product does not. |
| Photo geolocation | **Partial components only** | No complete evidence-ranked location reconstruction contract yet. |
| Remediation/export | **Useful base** | Worth preserving, but dependent on better discovery. |
| Performance | **Known discovery bottlenecks** | Fixed batches, serial plugin families, shallow bounded search. |
| Real-world recall benchmark | **Absent** | Primary reason mission readiness is unscored. |
| Physical-device acceptance | **Not established** | Prior evidence is primarily emulator-based. |

## 22. Immediate next audit/implementation tranche

The next code tranche should be narrow enough to validate independently:

1. fix current provider-audit CI failure;
2. remove hard-coded device/repository runtime identifiers;
3. remove committed non-product state and obvious marker/redundant artifacts;
4. consolidate only clearly overlapping tests;
5. add repository PII/hygiene checks;
6. implement the universal launch screen and seed classifier without deleting useful downstream screens yet;
7. create the synthetic end-to-end discovery benchmark skeleton;
8. replace WhatsMyName fixed batch execution with rolling scheduling;
9. parallelize independent plugin families under a bounded coordinator;
10. introduce the initial canonical Exposure Fact/Ledger model and adapt existing evidence into it.

This tranche should be validated before broad source expansion or photo-provider work.

## 23. Production-readiness statement

Dossier is **not production-ready under the new product mission**.

That statement does not mean the codebase is worthless or must be rewritten. It means the project has a stronger infrastructure base than its current real-world discovery usefulness.

The reset goal is to convert that base into a product whose usefulness can be demonstrated with measured recall, precision, exact-value recovery, derivation paths, and time-to-result rather than inferred from architecture size.

## 24. Rule for future updates to this file

Every future `TRUTH.md` update must distinguish:

```text
implemented
validated
benchmarked
production-ready
```

These are different states.

Do not restore a global numeric readiness score until the end-to-end discovery benchmark exists and has been run against a representative synthetic/consented corpus.

Do not claim a source as automated because it appears in a catalogue.

Do not claim an exact exposure value is recoverable unless Dossier actually recovered it from an accessible/authorized source in the tested flow.

Do not claim photo location accuracy from visual plausibility alone.

Do not claim a face/account identity from similarity alone.

The product is successful only when the evidence proves it.