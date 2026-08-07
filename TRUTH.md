# Dossier — Implementation Truth

This is the sole live implementation/readiness record for Dossier. `AGENTS.md` defines the target; this file describes reality.

## Current snapshot

- **Strict product readiness score:** **62/100**
- **Milestone state:** M0 complete; M1–M5, M8–M12 materially advanced but still partial; M6/M7/M13 remain partial
- **Current implementation branch:** `feat/product-contract-discovery-v2`
- **Last validated implementation commit:** `0988ae6488b24767a5455a5c90bc4b98e7407eed`
- **Validated CI on that commit:** provider-registry audit, face-calibration runtime check, JVM unit tests, debug APK assembly, and all 8 API 35 Compose instrumentation tests
- **Last validated device class:** Pixel 6 profile, Android API 35, x86_64 emulator
- **Real-device production validation:** not yet recorded
- **Declarative provider definitions:** **78 authored**
- **Registry-wide live provider validation:** not established
- **Production readiness:** not established

Compilation, unit tests and emulator tests are necessary gates, but they are not substitutes for live-provider validation, calibrated identity/face benchmarks, real-device measurements, accessibility validation or release hardening.

## Strict 100-point rubric

| Area | Score | Truth |
|---|---:|---|
| Discovery breadth and reliability | **9/15** | Discovery Fabric v2 is integrated into runtime scan planning with 78 declarative definitions, categories, validation, health primitives, scan modes, maintenance audit CI and compatibility execution. It remains far below the 1,000+ reviewed target and lacks registry-wide live validation and universal v2 scheduling. |
| Recursive orchestration | **5/10** | A central coordinator/event bridge observes and starts the mature pipeline, live UI consumes real event-derived state, and two-hop discovery now has deterministic admission/visited/depth/budget rules. True in-flight pause/resume, sole coordinator ownership, per-provider lifecycle events and a general persisted frontier remain incomplete. |
| Evidence/provenance | **6/10** | The universal evidence model now supports provider, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical state. Verification is explicitly separate from numeric confidence. Not every producer populates every provenance field yet. |
| Entity resolution | **5/10** | An explainable multi-signal resolver is integrated into the production graph, preserves feature contributions and contradictions, and prevents same-username-only confirmation. Weights remain conservative engineering parameters rather than benchmark-calibrated probabilities. |
| Identity graph | **6/8** | Graph v2 adds semantic node kinds, typed relationships, evidence/contradiction IDs, node states, history fields, queries and schema versioning while preserving legacy case compatibility. Some subsystems still retain parallel legacy representations and not every edge has complete evidence linkage. |
| Image acquisition/correlation | **5/8** | Public image candidates, exact/perceptual hashing, crop variants and local comparison are functional. First-class acquisition provenance/clustering across the entire graph and full image-cluster investigation UX remain incomplete. |
| Face-correlation validation | **3/6** | YuNet/SFace artifacts are pinned and integrity checked; preprocessing, alignment, quality rejection and calibration tooling exist and its runtime check passes CI. Representative measured FAR/FRR/ROC results and real-device inference validation do not exist yet. |
| Historical evidence | **3/6** | Exact-URL Wayback recovery exists and a timestamp-disciplined identity timeline builder now separates current, historical and breach events without inventing dates. Universal archive metadata propagation, broader extraction and timeline UI remain incomplete. |
| Breach intelligence | **4/5** | HIBP authoritative coverage remains separate from general public exposure; privacy-preserving range flows are used where supported; breach/provider/retrieval/date metadata is preserved for timeline use. Coverage still depends on compatible user credentials/provider availability and full investigation UX is not finished. |
| AI analyst | **4/5** | Generated factual claims must conform to structured output, cite existing evidence IDs and survive deterministic validation; hallucinated/uncited claims are withheld and invalid output falls back locally. Graph/correction/remediation-native prompts and a production AI evaluation corpus remain incomplete. |
| UX/UI | **6/8** | Overview/Evidence/Connections/Actions remain coherent; live scan state is event-derived; saved Cases now support persistent evidence/account corrections and remediation tracking; share-safe export is exposed. Timeline/image-cluster UX, corrections directly on live Evidence cards and broad accessibility/large-layout validation remain incomplete. |
| Security/privacy | **3/4** | Keystore-backed AES-256-GCM cases, restricted evidence WebView, local visual processing defaults, opt-in remote AI, explicit case deletion and pre-write share-safe export redaction are implemented. The complete security/privacy test matrix and more granular export-redaction controls remain incomplete. |
| Testing/device validation | **3/5** | Provider audit, calibration runtime, JVM tests, APK assembly and 8 Compose tests pass on API 35, including encrypted correction/remediation persistence. Samsung/Pixel physical-device, lower-memory, battery, process-death, font-scale and accessibility gates are not complete. |
| **Total** | **62/100** | A materially integrated product-contract tranche is validated, but several empirical and production-hardening gates still cap readiness. |

## M0 — Baseline audit

**Status: complete for this branch.**

The current architecture was inspected and preserved rather than reorganized for style. Existing evidence, graph, image/face, breach, AI, case, export and Compose behavior remains the foundation of the new contract work.

Primary production structure remains:

```text
app/src/main/java/io/dossier/app/
  data/       network services, provider catalog, models and persistence
  domain/     scanner, discovery, evidence, graph, analysis and case logic
  export/     PDF + JSON evidence export
  ui/         Compose screens/navigation/components/theme
```

## M1 — Discovery Fabric v2

**Status: partial, production-integrated.**

Implemented:

- typed `ProviderDefinition` schema;
- provider categories, query capabilities and reliability classes;
- declarative existence/extraction/request-policy rules;
- duplicate-ID/template/priority/status/request-policy validation;
- Quick / Standard / Deep / Exhaustive scan modes;
- scan-depth UI backed by real runtime preferences;
- actual direct-provider fan-out changes by scan mode;
- Deep/Exhaustive integration with the existing bounded extended/historical path;
- scan-mode persistence in resume markers with backward-compatible Standard migration;
- deterministic response classification for present, not-found, soft-404, authentication-required, automation-challenged, external-redirect, unexpected and invalid responses;
- process-local provider health statistics;
- **78 authored provider/service definitions**;
- legacy `PLATFORMS` compatibility generated from the v2 registry rather than maintained separately;
- deterministic registry maintenance audit tool and CI gate;
- parser-drift detection so the audit cannot silently undercount provider definitions;
- sampled advisory source canaries.

Not complete:

- 1,000+ useful reviewed definitions;
- registry-wide live existing/missing/soft-404/redirect/challenge validation;
- universal runtime use of v2 per-provider request intervals/concurrency/cooldowns;
- health persistence/history across processes;
- migration of every mature custom resolver to declarative classification;
- import/maintenance tooling appropriate for thousands of definitions.

A schema-valid provider is not counted as live validated merely because it exists in the catalog.

## M2 — Scan Coordinator + live events

**Status: partial, production-integrated.**

Implemented:

- `ScanId`, `ScanRequest`, `ScanRunState`, `ScanEvent` and `LiveScanSnapshot`;
- `ScanCoordinatorRuntime` wraps and can start/cancel the mature `ScanSession` pipeline;
- events reflect real stage, profile-batch, face, breach, graph, analysis, completion and cancellation state;
- production scan UI uses event-derived profile/entity/finding counters;
- no invented provider-completion events.

Not complete:

- true suspended pause/resume execution state;
- per-provider queued/started/completed/unavailable events from the underlying scheduler;
- sole coordinator ownership of every scan entry path;
- persisted scan event history and crash-recovery checkpoints.

## M3 — Recursive frontier

**Status: partial, integrated with the existing bounded two-hop scanner.**

Implemented:

- explicit pivot-admission policy;
- depth overflow rejection;
- visited-signal/URL rejection;
- shared bounded pivot budget;
- common/generic handle corroboration requirements;
- weak name/location/occupation/face-only signals cannot recursively fan out by themselves;
- explicit public cross-links can expand;
- second-hop expansion remains limited to verified results.

Not complete:

- a general persisted multi-signal frontier independent of legacy profile-candidate mechanics;
- explicit stored rejected-pivot diagnostics in cases;
- configurable per-signal recursion budgets in production UI.

## Evidence and provenance

The existing universal `Evidence` model was evolved rather than replaced by a second truth system.

It can now retain:

```text
provider ID
source URL
evidence kind
retrieved timestamp
observed timestamp
verification state
source reliability
content SHA-256
parser version
historical/current state
confidence
```

Important invariant: a high legacy confidence value does **not** automatically become `Verified`.

Remaining gap: older producers do not yet populate every provenance field consistently.

## M4 — Identity Graph v2

**Status: partial, production-integrated.**

Implemented additively:

- semantic `GraphEntityKind` covering the broader product taxonomy;
- stable legacy `EntityType` retained for saved-case compatibility;
- typed relationship taxonomy;
- node verification/conflict state;
- edge evidence IDs;
- contradiction IDs;
- historical flags;
- first/last observation fields;
- graph schema version;
- graph queries for relationship/evidence/historical/conflicting branches;
- legacy node/relation adapters automatically populate v2 semantics;
- existing graph builder feeds the v2 model used by current analysis/UI.

Not complete:

- every subsystem reading/writing only the graph as sole truth;
- complete evidence IDs on every legacy edge;
- full archive/image/breach graph population at contract depth.

## M5 — Entity Resolver v2

**Status: partial, production-integrated but uncalibrated.**

Implemented:

- explainable correlation contributions;
- independent signals including explicit supplied URL, direct verification, cross-links, exact public email, name, organization and location;
- contradiction handling for strongly incompatible names/evidence;
- conservative confidence bands and node-state enrichment;
- explicit regression test that the same username alone cannot confirm identity.

Not complete:

- representative consented/synthetic benchmark at production scale;
- precision/recall/FPR/FNR/calibration publication;
- empirically calibrated feature weights and decision bands.

False positives remain a primary release blocker.

## M6 — Image acquisition + correlation

**Status: partial; prior working functionality preserved.**

Implemented:

- public profile/image-index candidate acquisition;
- local SHA-256, aHash, dHash and pHash comparison;
- histogram comparison;
- crop/resize variants;
- local candidate verification and visual-support findings;
- image candidate source URLs retained in current findings/results.

Not complete:

- one first-class `ImageCandidateAcquirer` writing complete provenance for every candidate;
- durable exact/perceptual cluster objects in the case/graph;
- investigation-grade image-cluster review UI.

## M7 — Face validation

**Status: partial.**

Implemented:

- pinned OpenCV Zoo YuNet and SFace artifacts;
- exact size + SHA-256 integrity checks;
- atomic model installation;
- inference-time re-verification;
- deterministic color/resize/crop/alignment pipeline;
- five-landmark alignment;
- detection/quality/ambiguity rejection;
- cosine similarity;
- calibration utility with identity-disjoint train/test behavior and held-out metrics support;
- CI validates the calibration runtime/API surface.

Not complete:

- adequate consented or legally distributable benchmark run;
- published ROC/FAR/FRR results;
- demographic/device/pose/age evaluation at release quality;
- measured Samsung/Pixel/lower-memory thresholds, latency, thermal and battery behavior.

Reference thresholds remain engineering/reference values until measured.

## M8 — Historical identity

**Status: partial.**

Implemented:

- bounded exact-URL Wayback availability lookup;
- historical snapshot retrieval under size/content restrictions;
- historical verification independent of stale search snippets;
- historical confidence ceiling;
- current/historical labeling;
- `IdentityTimelineBuilder` using only real evidence timestamps/provider breach dates;
- untimestamped observations are omitted rather than assigned invented dates;
- current evidence, historical evidence and breach incidents remain distinct timeline types.

Not complete:

- general archive discovery beyond verified/exact URLs;
- complete extraction of historical username/avatar/bio/organization/location changes;
- universal archive timestamps propagated through every legacy finding adapter;
- production timeline screen.

## M9 — Breach intelligence

**Status: substantially implemented, still externally dependent.**

Implemented:

- password k-anonymity range lookup;
- authenticated email range lookup where provider access supports it;
- full addresses are not used as a silent fallback when the privacy-preserving email endpoint is unavailable;
- authoritative HIBP coverage remains distinct from public-web exposure;
- explicit not-configured/rejected/rate-limited/unavailable states;
- breach name/domain/date/data-class metadata;
- added/modified/provider/retrieval metadata retained for timeline/provenance;
- no leaked passwords or stolen credential database distribution.

Remaining limitations are primarily external access/coverage and UX integration.

## M10 — Evidence-grounded AI

**Status: partial, production-integrated.**

Implemented:

- deterministic structured evidence snapshot/prompt boundary;
- `AiAnalysisClaim` / `AiAnalysisResult` structured output;
- factual claims require cited evidence IDs;
- nonexistent evidence IDs are rejected;
- uncited factual claims are rejected;
- contradictory HIGH claims are downgraded to conflicting;
- output count/length bounds;
- raw generated prose is not displayed merely because generation succeeded;
- malformed/unsupported/hallucinated output falls back to deterministic on-device analysis;
- remote AI remains opt-in/disclosed.

Not complete:

- model input fully derived from corrected graph/remediation state in every call path;
- production evaluation corpus covering hallucination, contradiction and sensitive-data leakage;
- stronger redaction controls for remote-model input.

## M11 — Investigation UX

**Status: partial.**

Implemented/preserved:

- coherent Overview / Evidence / Connections / Actions structure;
- event-derived live scan state rather than fabricated counters;
- evidence/source confidence/risk distinction;
- interactive graph surface and accessible textual alternatives from prior work;
- saved-case before/after comparison;
- saved-case evidence corrections;
- saved-case account attribution decisions;
- remediation state tracking;
- share-safe report action;
- 48dp action targets for the newly added review/remediation controls.

Not complete:

- full timeline UI;
- first-class image cluster review;
- corrections directly from every live Evidence card;
- mature adaptive tablet/landscape layout;
- complete localization/RTL and accessibility validation.

## M12 — Remediation + differential rescan

**Status: partial, now user-visible in saved Cases.**

Case schema v3 can persist:

```text
authorized scope
scan history
user corrections
remediation records
export records
```

Implemented:

- `This is me` / `This is not me` / `Unsure` / `Ignore evidence` persistence;
- corrections affect effective analysis/graph membership without deleting raw evidence;
- `This is not me` can force conflicting/zero-confidence linkage;
- remediation states: Not started, In progress, Submitted, Awaiting response, Completed, Rejected, Needs manual action;
- user-facing remediation controls in saved Cases;
- case diff: added/removed/changed/unchanged findings;
- remediation recheck distinguishes `StillObserved` from `NotObservedInLatestScan`;
- UI explicitly says `Completed` is a workflow state, not proof that remote data is gone;
- one later scan not observing something does not claim global deletion.

Not complete:

- provider-specific deep links/removal forms for broad provider coverage;
- automatic association of every new scan with a prior remediation record;
- explicit live/search-index/archive three-way removal verification UI;
- scan-history persistence from coordinator lifecycle rather than only available schema APIs.

## Reports and export

Full exports still provide PDF + JSON evidence packages with section SHA-256 hashes and a manifest hash. These hashes are integrity metadata, not independent attestation.

A `ShareSafe` redaction mode is now implemented and exposed from saved Cases. Redaction occurs **before export files are written** and generalizes/removes:

- subject name;
- finding values;
- source URLs;
- evidence snippets;
- profile summary details;
- visual source URLs;
- breach identifier details;
- graph labels/details;
- generated AI analysis that may reproduce identifying evidence.

The package records its redaction mode. The UI warns that redaction reduces disclosure but cannot guarantee anonymity and the user must review generated files before sharing.

Remaining work includes configurable field-level redaction and broader export tests across complex cases.

## Security and privacy

Implemented controls include:

- Android Keystore-backed AES-256-GCM case storage;
- versioned encrypted case schema;
- atomic case writes and integrity verification;
- no plaintext fallback for new saves;
- explicit case deletion;
- Keystore-backed secret storage where configured;
- restricted evidence WebView defaults;
- system Photo Picker rather than broad media permission;
- local visual/face processing by default;
- explicit strong face-correlation choice;
- optional/disclosed remote AI;
- share-safe export redaction before file creation;
- no newly introduced challenge bypass, credential acquisition, private-source access, hidden tracking or traffic-evasion behavior.

## Validation record

Validated implementation commit:

```text
0988ae6488b24767a5455a5c90bc4b98e7407eed
```

Passing gates on that exact commit:

```text
Provider registry audit       PASS
Face calibration runtime      PASS
JVM unit tests                PASS
Debug APK assembly            PASS
API 35 Compose tests (8/8)    PASS
```

The Compose suite includes consent/navigation/identity validation, scan-mode selection and an encrypted saved-case round trip proving that a user correction and remediation status survive persistence.

This is emulator validation only. No physical device is recorded as release-validated yet.

## Current production blockers

1. Grow Discovery Fabric from 78 definitions toward the contract's 1,000+ useful reviewed providers with automated import/maintenance and real provider-contract validation.
2. Make v2 scheduling universal: per-provider rate policy, provider-level lifecycle events, persisted health and truthful per-provider progress.
3. Implement true coordinator-owned pause/resume/crash recovery and a persisted general recursive frontier.
4. Calibrate entity resolution with a representative same-person/different-person benchmark and publish precision/recall/FPR/FNR/calibration results here.
5. Finish universal evidence/provenance population and make the graph the sole internal truth source.
6. Build first-class image candidate/cluster provenance and investigation UI.
7. Run the face-correlation benchmark and publish measured ROC/FAR/FRR thresholds; then validate on representative physical devices.
8. Complete historical extraction and a production timeline UI.
9. Build a production AI evaluation corpus and corrected-graph-native model input path.
10. Complete live Evidence correction UX and broad provider-specific remediation actions/recheck workflows.
11. Perform physical Samsung/Pixel/lower-memory QA, process-death/background recovery, font/display scale, TalkBack/switch/keyboard, battery/network/thermal profiling and large-case performance testing.
12. Complete release/security hardening and packaging.

## Non-negotiable limitations

- Dossier cannot guarantee discovery of private, authenticated, never-indexed or never-archived content.
- Provider availability, indexing and API access are externally controlled.
- Search results and visual/face similarity remain evidence leads until corroborated.
- Historical snapshots may be missing, stale or incomplete.
- Face architecture and model integrity do not establish real-world recognition accuracy.
- Authoritative HIBP account coverage depends on compatible user-supplied access and provider availability.
- `NotObservedInLatestScan` is not equivalent to verified deletion.
- Emulator CI cannot substitute for representative physical-device testing.

## Documentation policy

Do not create separate roadmap, audit, status, progress or handoff Markdown files. `AGENTS.md` is the target contract; update this document after meaningful validated milestones and remove superseded claims instead of accumulating contradictory records.
