# Dossier — Implementation Truth

This is the sole live implementation/readiness record for Dossier. `AGENTS.md` defines the target; this file describes reality.

## Current snapshot

- **Strict product readiness score:** **66/100**
- **Milestone state:** M0 complete; M1–M6 and M8–M12 materially advanced but still partial; M7 and M13 remain partial
- **Current implementation branch:** `feat/product-contract-discovery-v2`
- **Last validated implementation commit:** `4f104115a8c60b57f71078dedf1e4528a2b19949`
- **Last validation date:** **2026-08-24**
- **Current-session validation on that commit:** 53 focused checkpoint/executor/worker/startup JVM tests with zero failures/errors/skips, Android instrumentation compilation, and an independent 72-test lifecycle/checkpoint review with zero failures/errors/skips. A targeted API 36 Keystore run built but executed zero tests because the emulator disconnected during APK installation; runtime validation for this exact commit remains open.
- **Documentation-only commits after the validated implementation:** may update this file/PR prose without changing the validated code claim
- **Last validated device class:** Medium_Phone profile, Android API 36, x86_64 emulator
- **Real-device production validation:** not yet recorded
- **Declarative provider definitions:** **78 authored**
- **Pinned WhatsMyName catalog:** **716 source records; 644 executable HTTPS username rules after parser/policy filtering; 72 non-executable under current policy**
- **Registry-wide live provider validation:** not established
- **Production readiness:** not established

Compilation, unit tests and emulator tests are necessary gates, but they do not substitute for live-provider validation, calibrated identity/face benchmarks, physical-device measurements, accessibility validation or release hardening.

The readiness score is **66/100** after request-scoped recovery for the initial direct-profile pass. Exact, unexpired stable outcomes are encrypted per request/plan/candidate and can be restored without synthetic queue/fetch events; transient failures are never reused. This is useful partial recovery, not full scan pause/resume: pivots, search, images, breach work, AI work and the general recursive frontier remain uncheckpointed.

## Strict 100-point rubric

| Area | Score | Truth |
|---|---:|---|
| Discovery breadth and reliability | **11/15** | Discovery Fabric v2 is integrated into runtime planning and direct-profile execution with 78 declarative definitions, categories, validation, bounded reads, timeout/retry/cooldown/request-spacing policy, typed truthful outcomes, provider health updates, scan modes and maintenance-audit CI. A separately pinned WhatsMyName asset contains 716 source records and 644 executable HTTPS username rules after parser/policy filtering, with mode budgets, bounded execution and fail-closed states. These rules are not additional `ProviderCatalogV2` definitions or live-validated providers. The project remains below the 1,000+ useful reviewed-provider target and lacks registry-wide live contracts. |
| Recursive orchestration | **6/10** | The coordinator/event bridge starts and observes the mature pipeline; direct-profile work emits real queued/started/completed/unavailable events under a stable scan ID; live UI consumes bounded event-derived counts; two-hop discovery has admission/visited/depth/budget rules; real terminal scan history is captured. Production background scheduling uses encrypted two-phase request publication, exact UUID/generation ownership, startup `getWorkInfoById` reconciliation, idempotent result-aware success and terminal cancellation observation. The initial direct-profile pass now checkpoints stable candidate outcomes per exact request/plan and resumes misses only. True in-flight pause/resume, sole coordinator ownership, a general persisted frontier/plan and external process-kill/reboot proof remain incomplete. |
| Evidence/provenance | **6/10** | Universal evidence supports provider, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical state. Imported third-party OSINT reports are explicitly Candidate/ThirdPartyAggregation and never become Verified merely because an external tool reported a hit. Older producers still do not populate every field universally. |
| Entity resolution | **5/10** | An explainable multi-signal resolver is integrated into the production graph, preserves contributions and contradictions, and prevents same-username-only confirmation. Direct profile verification now keeps handle-only/name-only matches below the attribution threshold unless independently corroborated or explicitly supplied. Weights remain engineering parameters rather than benchmark-calibrated probabilities. |
| Identity graph | **6/8** | Graph v2 adds semantic node kinds, typed relationships, evidence/contradiction IDs, node states, history fields, queries and schema versioning while preserving legacy case compatibility. GraphML, declared-attribute GEXF and NetworkX-style node-link JSON serializers provide dependency-free interoperability with Gephi/Cytoscape/NetworkX-style workflows. Some subsystems still retain parallel representations. |
| Image acquisition/correlation | **6/8** | Public candidate acquisition, local SHA-256/aHash/dHash/pHash/histogram/crop comparison, first-class candidate provenance, stable candidate IDs, exact/perceptual duplicate clusters and inspectable Reverse Media provenance UI are implemented. Google Lens remains an external/manual reference; Yandex candidate discovery is not identity proof. Candidate/cluster persistence into encrypted cases and the main identity graph remains incomplete. |
| Face-correlation validation | **3/6** | YuNet/SFace artifacts are pinned and integrity checked; preprocessing, alignment, quality rejection and calibration tooling exist and its runtime check passes CI. `face_recognition` and DeepFace are catalogued as alternative ecosystems rather than embedded dependencies because Dossier already has a local consented pipeline. Representative measured FAR/FRR/ROC results and physical-device inference validation do not exist yet. |
| Historical evidence | **3/6** | Exact-URL Wayback recovery, bounded CDX history discovery, selected-snapshot re-fetching and best-effort archive.today fallback exist. Historical captures remain explicitly historical and cannot masquerade as proof of current account existence. Universal historical extraction/change tracking and production timeline UI remain incomplete. |
| Breach intelligence | **4/5** | HIBP authoritative coverage remains separate from general public exposure; privacy-preserving range flows are used where supported; breach/provider/retrieval/date metadata is preserved. LeakCheck/DeHashed/Breach-Parse/Buster/BreachFinder interoperability is summary-only and rejects credential/secret-bearing records. Coverage remains externally dependent. |
| AI analyst | **4/5** | Generated factual claims must be structured, cite existing evidence IDs and survive deterministic validation; uncited/unknown identifiers are withheld and invalid output falls back locally. The validator also rejects explicit email/URL identifiers not present in the evidence actually cited. Corrected-graph/remediation-native inputs and a production evaluation corpus remain incomplete. |
| UX/UI | **6/8** | Overview/Evidence/Connections/Actions remain coherent; live scan state is event-derived; saved Cases support persistent corrections, remediation tracking, scan-history display and share-safe export. API 36 visual QA fixed consent-footer overlap, machine-token status copy, graph-label clipping, remediation-status overlap, stale scan logs, the background/cancel action collision, ambiguous provider budgets and large-type navigation/count wrapping. The changed scan-budget and navigation states were inspected at 1.0x, 1.3x, 1.5x and 2.0x font scale. Timeline UX, graph-export UI, case-integrated image clusters and broad TalkBack/adaptive-layout/physical-device validation remain incomplete. |
| Security/privacy | **3/4** | Keystore AES-256-GCM cases, encrypted request-scoped scan checkpoints, generation-bound exact-owner lifecycle state, opaque new WorkManager inputs, allowlisted WorkManager progress/error values, atomic encrypted result replacement, restricted evidence WebView, local visual processing defaults, opt-in remote AI, explicit deletion and pre-write share-safe export are implemented. External report imports are bounded, local/in-memory, tied to explicit audit seeds and strip/reject password/hash/cookie/token/session/credential material. Historical plaintext WorkManager rows are not forensically erased, and latest-result reads still need bounded/off-main hardening. |
| Testing/device validation | **3/5** | Provider audit (78 definitions), pinned WhatsMyName integrity audit (716 records / 644 executable rules), both debug and uiTest JVM suites (462 tests across 88 suites each, 0 failures/errors/skips), debug/uiTest APK assembly, zero-error lint and a clean-state API 36 Compose/instrumentation run (21/21), including Android Keystore resume tests (2/2), exact-UUID WorkManager lifecycle/crash-boundary tests (5/5) and uiTest-only encrypted-data/provider-progress fixtures (2/2), pass on the exact implementation commit above. Fifteen canonical API 36 screenshots and changed states at 1.0x/1.3x/1.5x/2.0x font scale were inspected, but physical Samsung/Pixel/lower-memory, battery, external process-death/reboot and complete accessibility gates remain incomplete. |
| **Total** | **66/100** | Stable initial direct-profile work is now recoverable under an exact encrypted request/plan scope. This does not establish full-pipeline pause/resume, process-kill/reboot acceptance, registry-wide live validation, empirical calibration or release hardening. |

## M0 — Baseline audit

**Status: complete for this branch.** Working evidence, scanner, graph, visual, breach, AI, case, export and Compose architecture was preserved rather than rewritten for package/style purity.

## M1 — Discovery Fabric v2

**Status: partial, production-integrated.**

Implemented:

- typed `ProviderDefinition` schema;
- provider categories, query capabilities, source reliability and bounded request policies;
- declarative existence/extraction rules;
- duplicate/template/priority/request-policy validation;
- deterministic response classification for present, not-found, soft-404, authentication-required, automation-challenged, redirect, unexpected and invalid responses;
- Quick / Standard / Deep / Exhaustive modes backed by actual runtime plans;
- scan-mode persistence in resume markers;
- process-local provider health statistics;
- **78 authored provider/service definitions**;
- a separately pinned WhatsMyName catalog from upstream commit `e62338e4fc88536a330733d355a9d33a3a1697c6`, with bundled CC BY-SA 4.0 license/notice and exact data/license SHA-256 checks;
- strict parsing and policy filtering of 716 source records into 644 executable HTTPS username rules and 72 explicitly non-executable records;
- Quick / Standard / Deep / Exhaustive username-rule budgets of 50 / 200 / 500 / 644 per normalized explicit handle, at most three handles, bounded concurrency and response sizes;
- fail-closed catalog-unavailable and provider outcomes, Observed evidence only, and explicit UI separation of direct-profile counts from username-rule budgets;
- direct-profile execution through definitions with bounded response reads, policy timeouts, retries, cooldowns, minimum request spacing and redirect enforcement;
- typed `RateLimited`, `Timeout` and `NetworkUnavailable` outcomes alongside present/not-found/auth/challenge/redirect/parser states;
- provider IDs propagated from templates through candidates/results into evidence;
- stable-scan-ID queued/started/completed/unavailable callbacks and persistent health outcome updates;
- legacy `PLATFORMS` compatibility generated from the v2 registry;
- deterministic provider-registry audit tool and CI;
- audit parser-drift detection and canonical TRUTH count enforcement;
- sampled advisory provider canaries;
- a public-source capability catalog separating Native, NativeEquivalent, ImportOnly, API-key-dependent, ManualOnly, Retired and Unsupported integration postures;
- explicit truthful states for retired Google/Bing cached-page products rather than simulated support.

Not complete: 1,000+ useful reviewed providers, registry-wide live present/missing/soft-404/redirect/challenge validation (including representative WhatsMyName rules), persisted provider-health history, and migration of every mature custom resolver to the declarative runtime.

## OSINT interoperability tranche

**Status: validated as bounded interoperability, not equivalent to native provider coverage.**

### Native / native-equivalent capabilities

- Reddit exact-author public post search and bounded Shreddit comment search, with direct Reddit re-fetch before promotion to Verified;
- Wayback exact-URL recovery plus bounded CDX history discovery and selected snapshot verification;
- best-effort archive.today/archive.ph exact-URL fallback without challenge bypass;
- richer local EXIF parsing for user-selected media as an ExifTool-equivalent path;
- Dossier's existing consented local face pipeline instead of embedding `face_recognition`/DeepFace runtimes;
- GraphML, GEXF and node-link JSON graph interoperability for Gephi/Cytoscape/NetworkX/Graphistry-style downstream analysis.

### Local import interoperability

The identity flow accepts bounded user-selected JSON/JSONL/CSV/TSV/text reports. Compatible producers include, depending on report contents and explicit audit seeds:

- SpiderFoot OSS;
- Recon-ng;
- theHarvester;
- Maigret;
- Sherlock;
- Holehe redacted/account-existence summaries;
- Twint JSON and snscrape JSONL;
- Pushshift exports;
- OSINTgram;
- Instaloader public exports;
- facebook-scraper public-page exports;
- Social Analyzer;
- public LinkedIn scraper exports;
- GeoSocial/geotag reports;
- LeakCheck/DeHashed/Breach-Parse/Buster/BreachFinder redacted summaries;
- OpenCorporates reports;
- GitHub OSINT/profile summaries and GH Archive-derived reports;
- Image Analyzer metadata reports;
- PhoneInfoga;
- Numverify JSON/text responses for explicitly supplied phone numbers;
- FOCA metadata reports;
- Censys/Shodan reports limited to explicit in-scope infrastructure;
- OWASP Amass reports limited to explicit in-scope domains.

Import invariants:

1. External tools are not launched by Dossier merely because their format is supported.
2. A record must tie back to an explicit authorized seed appropriate to that source family.
3. Imported third-party records are `Candidate` / `ThirdPartyAggregation`, never `Verified` by import alone.
4. Passwords, hashes, cookies, session tokens, API secrets, private keys and credential material are stripped or cause sensitive breach rows to be rejected.
5. Direct public URLs may subsequently be re-fetched by Dossier's verifier and only then gain stronger evidence state.
6. Infrastructure imports are limited to explicit domains/URLs already in scope rather than being used for person enumeration.
7. Dark-web tools remain manual/reference-only; Dossier does not automatically crawl Tor to search for people or collect credential dumps.

### Manual/reference-only integrations

Current product boundaries intentionally keep these human-driven rather than automated:

- TweetStamp and volatile Nitter instances;
- Google Earth / Street View;
- GeoSpy and GeoGuessr-style visual-location techniques;
- OpenStreetMap / Overpass place cross-checks;
- MCA India and Zauba corporate-record cross-checks;
- Google Lens reverse-image reference;
- OnionScan/Ahmia/Tor Browser workflows;
- hosted Graphistry workflows.

Manual/reference support means Dossier can preserve/cross-check resulting public evidence where appropriate; it does **not** mean Dossier embeds or automates the external service.

## M2 — Scan Coordinator + live events

**Status: partial, production-integrated.**

Implemented in the current production path:

- `ScanId`, `ScanRequest`, `ScanRunState`, `ScanEvent`, `LiveScanSnapshot`;
- coordinator start/cancel wrapper around the mature `ScanSession`;
- real stage/profile/face/breach/graph/analysis/completion/cancellation observations;
- production live UI counters from real session and provider-execution state;
- real direct-profile provider queued/started/completed/unavailable events with bounded counter semantics, retry isolation and stale-scan callback rejection;
- real scan lifecycle metadata including start/end, mode, provider-plan size, terminal counts and cancellation state;
- PII-safe SHA-256 seed fingerprint binding for associating a completed scan with an initial explicit case save;
- later correction/remediation edits cannot silently graft a newer scan onto an older case;
- request-scoped encrypted seed, mode, deep-scan and per-scan face-policy restoration through a canonical opaque WorkManager UUID;
- request/plan/candidate-scoped encrypted checkpoints for stable initial direct-profile outcomes, miss-only continuation, deterministic output ordering and no synthetic provider events for restored hits;
- stable allowlisted WorkManager progress/failure values, fail-closed legacy-input handling and exact current-owner result publication;
- atomic old-generation to pending-generation lifecycle replacement, followed by exact prepared-request promotion and fixed-UUID WorkRequest publication;
- generation/owner/request-bound worker claim, result publication before lifecycle success and idempotent retry after a durable-success/WorkManager-commit crash boundary;
- process-startup exact-ID reconciliation that retries unavailable lookups without mutation and can promote/re-enqueue only the persisted pending generation under the same UUID;
- durable cancel intent plus exact WorkInfo terminal observation, with success/failure races routed through result-aware reconciliation;
- process-local worker serialization plus cancellation propagation through the scanner/plugin paths touched by this tranche;
- failed scans route to the analysis/error surface rather than being announced as completed reports.

Supporting lifecycle stores use encrypted request `prepare` / exact-ID load / `promote` / discard operations, durable deletion guards, immutable generation/owner/request records, safe error codes and full-snapshot compare-and-transition operations. Reconciliation distinguishes authoritative missing work from an unavailable lookup and never adopts a unique-work-list result by ordering.

Not complete: true suspended pause/resume, sole coordinator ownership, persisted pivot/search/image/breach/AI work, a general plan/parser/frontier generation, migration of remaining custom resolver work to provider lifecycle events and external process-kill/relaunch plus reboot recovery proof.

The encrypted resume record stores the seed input, selected mode, deep-scan flag and per-scan strong-face-policy flag. New WorkManager rows receive only opaque request/generation UUID references. Prepared/current generations are still request checkpoints, not a persisted scan plan/parser/frontier fingerprint, and deterministic crash-boundary/instrumentation coverage does not by itself constitute process-death or reboot acceptance.

## M3 — Recursive frontier

**Status: partial, integrated with the existing bounded two-hop scanner.**

Implemented: admission policy, visited/depth rejection, shared pivot budget, common-handle corroboration, weak-signal suppression and verified-only second-hop expansion.

Not complete: a general persisted multi-signal frontier, stored rejected-pivot diagnostics and configurable per-signal recursion budgets.

## Evidence and provenance

The universal `Evidence` model remains the canonical evidence layer. It can retain provider ID, URL, evidence kind, retrieval/observation timestamps, verification state, reliability, content SHA-256, parser version, historical/current state and confidence.

**Invariant:** a high numeric confidence value does not automatically become `Verified`.

**Import invariant:** a third-party scanner/export never becomes `Verified` simply because it reported a match.

Remaining gap: older producers do not yet populate every provenance field consistently.

## M4 — Identity Graph v2

**Status: partial, production-integrated.**

Implemented: full semantic `GraphEntityKind`, stable legacy type compatibility, typed relationship taxonomy, node state, edge evidence IDs, contradiction IDs, historical flags, first/last observations, graph schema version, relationship/history/conflict queries and standard graph serialization.

Not complete: graph-only truth across every subsystem, complete evidence IDs on legacy edges, graph-export UI/share-safe packaging, and full archive/image/breach population at contract depth.

## M5 — Entity Resolver v2

**Status: partial, integrated but uncalibrated.**

Implemented: explainable contributions, independent URL/verification/cross-link/email/name/organization/location signals, contradiction handling, conservative confidence bands and regressions proving a shared username/name alone cannot confirm identity.

Not complete: representative benchmark, precision/recall/FPR/FNR/calibration publication and empirically fitted weights/thresholds.

## M6 — Image acquisition + correlation

**Status: partial, vertically integrated.**

Implemented:

- public profile-avatar and image-index candidate acquisition;
- bounded candidate corpus and local comparison;
- SHA-256 exact matching;
- aHash, dHash and pHash;
- colour histogram comparison;
- full/centre/square crop variants;
- local near-duplicate/repost classification;
- stable candidate and duplicate-cluster identifiers;
- candidate provenance containing source, source page, acquisition query, compared URL, retrieval timestamp, SHA-256, dimensions, hashes, comparison score and truthful state;
- explicit UI wording that whole-image duplicate/repost similarity does not identify a person;
- generic Dossier HTTP identity rather than browser/device impersonation.

Not complete: persistence of image candidate/cluster objects into `DossierCase`, identity-graph edges connecting reused images across verified accounts, and cross-scan image-cluster history/diff.

## M7 — Face validation

**Status: partial.**

Implemented: pinned OpenCV Zoo YuNet/SFace models, exact size/SHA verification, atomic installation, deterministic preprocessing/alignment, five landmarks, quality/ambiguity rejection, cosine similarity and calibration tooling with identity-disjoint held-out support.

Not complete: adequate legal/consented benchmark, measured ROC/FAR/FRR, demographic/device/pose/age evaluation and representative physical-device latency/thermal/battery validation. Reference thresholds remain engineering values until measured.

## M8 — Historical identity

**Status: partial.**

Implemented: bounded exact-URL Wayback lookup, CDX history discovery for explicit profile/personal-site URLs, bounded direct snapshot re-fetch, best-effort archive.today fallback, archive-only verification, historical confidence ceilings, current/historical labeling and timeline construction from real timestamps only.

Not complete: extraction of historical username/avatar/bio/organization/location changes across representative providers, universal archive timestamp propagation and production timeline UI.

## M9 — Breach intelligence

**Status: substantially implemented, externally dependent.**

Implemented: Pwned Passwords range lookup, authenticated privacy-preserving email range lookup where supported, no full-address silent fallback, authoritative/public-web separation, explicit unavailable/configuration states and breach/provider/date/retrieval/data-class provenance.

Third-party breach-tool interoperability is intentionally summary-only. Credential material is not an import target.

## M10 — Evidence-grounded AI

**Status: partial, production-integrated.**

Implemented: deterministic structured evidence snapshot, structured claim/result contract, evidence-ID validation, uncited factual-claim rejection, unsupported email/URL identifier rejection, contradiction downgrade, output bounds, raw-prose rejection and deterministic local fallback.

Not complete: corrected graph/remediation-native input for every model path, production evaluation fixtures/corpus and stronger remote-input redaction controls.

## M11 — Investigation UX

**Status: partial.**

Implemented/preserved:

- Overview / Evidence / Connections / Actions;
- event-derived live scan state;
- risk/confidence distinction;
- graph + textual alternatives;
- saved-case before/after comparison;
- evidence/account corrections;
- remediation workflow controls;
- real scan-history summaries;
- remediation recheck states and explanations;
- share-safe report action;
- Reverse Media candidate provenance and duplicate/repost cluster inspection;
- user-visible local import controls for legacy Twint/snscrape plus broader external OSINT reports;
- human-readable presence/profile status copy without changing persisted domain enum tokens;
- measured/clamped graph labels that preserve complete short identifiers and ellipsize only when the canvas cannot fit them;
- remediation action/value/status rows that remain separate when action copy wraps;
- a deterministic uiTest-only `.test` fixture that exercises the production encrypted case/transient-result stores and is absent from the debug manifest.

Visual QA on the API 36 Medium Phone emulator produced and inspected 15 canonical screenshots covering consent, identity validation, username review, scan configuration/progress, analysis, all report tabs, saved cases, corrections and remediation. The accepted captures fixed consent copy under the sticky CTA, the raw `SuspiciousSimilarity` token, the clipped `jane@example.tes` graph label, remediation-status collision, stale scan-complete log, the Continue-in-background/Cancel overlap and ambiguous scan-budget presentation. `06-scan-configuration.png` now separates direct-profile counts from pinned HTTPS username-rule budgets; `07-scan-progress.png` shows unobstructed logs, separate action bounds and real scheduled/completed/unavailable counter semantics. The changed scan-budget cards and bottom navigation were inspected at 1.0x, 1.3x, 1.5x and 2.0x font scale; count competition and navigation-label ellipsis found during QA were fixed before acceptance. The provider-progress values are a deterministic uiTest-only lifecycle fixture and are not claimed as a live-network scan. Screenshots contain synthetic/non-personal test data only. This remains one viewport and affected-state scaling, not TalkBack, reduced-motion, landscape/tablet, whole-product accessibility or physical-device acceptance.

Not complete: production timeline, graph-export UI, case-integrated/cross-account image cluster review, corrections directly on every live Evidence card, mature tablet/landscape adaptation, localization/RTL and complete accessibility validation.

## M12 — Remediation + differential rescan

**Status: partial, user-visible in saved Cases.**

Implemented:

- case schema v3 fields for authorized scope, scan history, user corrections, remediation records and export records;
- real terminal coordinator lifecycle record attached only to a matching initial explicit encrypted case save;
- normalized identity-seed binding uses a SHA-256 fingerprint rather than a duplicate plaintext identity cache;
- `This is me`, `This is not me`, `Unsure`, `Ignore evidence` persistence;
- corrections alter effective analysis/graph membership without deleting raw evidence;
- remediation states: Not started, In progress, Submitted, Awaiting response, Completed, Rejected, Needs manual action;
- added/removed/changed/unchanged case diff;
- recheck states: `NotRechecked`, `StillObserved`, `NotObservedInLatestScan`, `StatusChanged`;
- user-visible wording that disappearance from one newer scan is not proof that search indexes, archives or every live copy are gone.

Not complete: broad provider-specific removal/deletion/correction links, automatic association of every later scan with earlier remediation records and explicit live/search-index/archive three-way removal verification.

## Reports and export

Full export provides PDF + JSON evidence packages with per-section SHA-256 hashes and a manifest hash. Hashes are integrity metadata, not independent attestation.

`ShareSafe` redaction is exposed from saved Cases and runs **before files are written**. It removes/generalizes identifying evidence and warns that redaction reduces disclosure but cannot guarantee anonymity.

Graph interoperability serializers now exist for GraphML, GEXF and node-link JSON, but they are not yet exposed as a polished share/export workflow. Any future external graph share must pass through the same redaction policy.

## Security and privacy

Implemented controls include Keystore-backed AES-256-GCM cases, versioned/atomic encrypted storage, opaque WorkManager request references, allowlisted background progress/error values, explicit deletion, restricted evidence WebView, system Photo Picker, local visual/face processing, opt-in remote AI, pre-write share-safe redaction, PII-safe scan-history binding and non-impersonating public-source HTTP identities.

The resume record is encrypted with AES-256-GCM in AndroidKeyStore. It stores the complete identity input, scan mode, deep-scan flag and per-scan face-policy flag behind UUID-named ciphertext files plus an opaque UUID pointer, authenticates the format/request ID with AAD, enforces a 24-hour TTL and bounded reads, and uses temporary-file publication with file and parent-directory fsync, rollback and a process-local lock. Key creation is fail-closed when prior encrypted state exists. Legacy plaintext UI markers migrate through a bounded validation/encryption/deletion policy, and typed internal read/write states preserve invalid, missing, expired and storage-failure distinctions.

New `BackgroundScanWorker` requests place only canonical opaque request/generation UUIDs in WorkManager input `Data`; output and relayed progress are bounded to fixed stage/error codes. The API 36 WorkManager tests inspect the current WorkSpec row and confirm the seeded identity value is absent. They do not inspect retained historical rows, SQLite WAL/free pages or prove forensic erasure. Pre-upgrade WorkManager rows may retain raw legacy `identity_json`/flags until WorkManager prunes them; the upgraded worker rejects those inputs without decoding or echoing their values.

Lifecycle hardening is production-integrated but not release-complete. Scheduling atomically publishes the replacement generation before cancelling the old exact UUID, startup reconciles only the persisted exact WorkManager UUID, result publication precedes durable success, retries are idempotent for an exact matching result and cancellation remains requested until the exact row is terminal. Initial direct-profile stable results use AES-GCM with request/plan/candidate AAD, bounded reads, atomic fsynced replacement, durable request tombstones and stable-only reuse. Remaining M2/M13 risks include external process-kill/relaunch and reboot proof, a crash/failed-cleanup window that can retain a prior encrypted profile scope until explicit purge or later maintenance, a wall-clock assumption in mismatched-result retirement, synchronous latest-result/purge call sites, unbounded result-envelope reads and general persisted frontier/plan recovery.

External OSINT imports are local/in-memory and bounded. Import parsers require explicit audit seeds appropriate to the source family and strip or reject secret-bearing fields. No newly introduced challenge bypass, credential acquisition, private-source access, hidden tracking, Tor person-crawling, authenticated social scraping or traffic-evasion behavior is permitted.

## Validation record

Validated implementation commit:

```text
4f104115a8c60b57f71078dedf1e4528a2b19949
```

Validation date:

```text
2026-08-24
```

Current-session validation on that exact commit:

```text
Focused checkpoint/lifecycle  PASS — 53 tests / 0 failures / 0 errors / 0 skips
Independent lifecycle review  PASS — 72 tests / 0 failures / 0 errors / 0 skips
Debug Kotlin compilation      PASS — warnings only
uiTest instrumentation compile PASS — warnings only
Targeted API 36 Keystore run  BLOCKED — APKs built; emulator disconnected during install; 0 tests executed
Visual delta                  NONE — persistence/recovery tranche does not change rendered UI
```

The unit suite includes regressions for external OSINT authorization boundaries, secret-bearing breach reports, Numverify phone scoping, graph interoperability, encrypted two-phase checkpoint persistence/deletion guards, generation-bound lifecycle transitions/startup retry, atomic result replacement, opaque WorkManager transport, fixed progress/error codes, graph-label placement, status copy and cancellation propagation. The API 36 WorkManager class exercises pending same-UUID reenqueuing, durable-success retry, exact terminal cancellation, stale-owner isolation and opaque WorkSpec input. The Compose harness validates one-time onboarding persistence across activity recreation, isolates onboarding state between tests and checks wrapped remediation/status bounds. The uiTest-only receiver writes deterministic `.test` data through the production encrypted stores and is absent from the debug manifest. WorkManager coverage remains scoped to newly created current rows and deterministic crash states; it is not historical/WAL forensic erasure, external process-death/reboot, accessibility or physical-device evidence.

## Current production blockers

1. Grow Discovery Fabric beyond 78 authored definitions and the separate 716-record/644-executable-rule pinned username catalog toward the contract's 1,000+ useful reviewed providers, with automated maintenance/import and representative live provider-contract validation.
2. Migrate remaining mature custom resolvers to the v2 runtime and persist provider health/reliability across process lifetimes.
3. Implement true coordinator-owned pause/resume and a persisted general recursive frontier/plan, then run external process-kill/relaunch and reboot recovery validation against the production lifecycle.
4. Move latest-result decrypt/deserialize/purge work fully off the main thread, bound result-envelope reads and run large-case ANR/storage-corruption tests.
5. Calibrate entity resolution with a representative benchmark and publish precision/recall/FPR/FNR/calibration results here.
6. Finish universal evidence/provenance population and graph-as-sole-truth migration.
7. Persist image candidate/cluster provenance into encrypted cases and the identity graph; correlate reused images across verified accounts and cross-scans.
8. Run the face-correlation benchmark and publish measured ROC/FAR/FRR thresholds, then validate on representative physical devices.
9. Complete historical attribute extraction and production timeline UI.
10. Build a production AI evaluation corpus and corrected-graph-native input path.
11. Complete live Evidence correction UX, graph export/share UX and broad provider-specific remediation/recheck workflows.
12. Perform physical Samsung/Pixel/lower-memory QA, process-death/background recovery, font/display-scale, TalkBack/switch/keyboard, battery/network/thermal and large-case performance testing.
13. Complete release/security hardening and packaging.

## Non-negotiable limitations

- Dossier cannot guarantee discovery of private, authenticated, never-indexed or never-archived content.
- Provider availability, indexing, licensing and API access are externally controlled.
- Search results, external scanner reports and visual/face similarity remain evidence leads until corroborated.
- A username, name, carrier/region result, image similarity result or historical capture alone does not prove identity.
- Import support for a third-party tool is not a claim that the tool is reliable, free, open source, currently maintained or legally usable in every jurisdiction/context.
- Dossier does not accept credential dumps, password/hash/cookie/session-token collections or private-source access as a product capability.
- Dark-web tooling remains manual/reference-only within separately authorized scope; Dossier does not automate person-focused Tor crawling.
- A source marked retired/degraded/best-effort remains visibly so; Dossier must not simulate a healthy provider.

## Production-readiness rule

The product reaches 100/100 only when the remaining blockers are implemented, independently validated and honestly recorded here. Adding more source names, import adapters or passing emulator CI cannot by itself satisfy that standard.
