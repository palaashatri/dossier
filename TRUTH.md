# Dossier — Implementation Truth

This is the sole live implementation/readiness record for Dossier. `AGENTS.md` defines the target; this file describes reality.

## Current snapshot

- **Strict product readiness score:** **83/100**
- **Milestone state:** M0 complete; M1–M6 and M8–M12 materially advanced but still partial; M7 and M13 remain partial
- **Current implementation branch:** `feat/product-contract-discovery-v2`
- **Last validated implementation commit:** `37f967d` (separate pinned WhatsMyName health diagnostics, known-provider health IDs, and case-insensitive graph relation-key merging; includes `ae7bfc8` bounded encrypted exposure checkpoint and terminal projection guard plus the prior tranches)
- **Last validation date:** **2026-08-25**
- **Current-session validation of the implementation plus the preserved pre-existing uiTest fixture edit:** provider and pinned-catalog audits passed; the provider-maintenance/audit fixture suite passed 11/11 and reports the pinned WhatsMyName hashes, 716 source records, 644 executable rules, 644/644 runtime-equivalent conversions, 0 conversion errors, 0 primary-catalog ID collisions and 72 policy exclusions without changing the 78-provider count; both debug and uiTest JVM suites passed 810 tests across 134 result XML files each; debug/uiTest APKs assembled; Android-test Kotlin compiled; lint completed with zero errors; and the complete connected uiTest suite passed 53/53 on the API 36 `medium_phone` emulator, including separate authored/WhatsMyName provider-health diagnostics, the relation-key graph regression, 8 WorkManager pause/resume tests, encrypted Activity-recreation recovery, bounded breach-stage behavior, bounded post-processing and graph-stage checkpoint/accessibility semantics, live finding/profile/media correction semantics, focused breach/report/reverse-media accessibility semantics, exact evidence-key fail-closed behavior, bounded CaseStore save behavior, graph/export redaction semantics, report-evidence semantics, async saved-case persistence, HUD status semantics and reverse-video picker semantics. A fresh external ADB `am force-stop`/relaunch and emulator-reboot smoke check on the current artifact restored the encrypted background result and confirmed the production `MainActivity` resumed after both launches. The exposure-checkpoint codec/store tests, terminal projection invariants, dangling-evidence audit, exact-only legacy provenance, exact resolver provenance, source-page-aware reverse-image candidate deduplication, graph relation normalization, checkpoint codec/store tests and WhatsMyName conversion parity are JVM/no-network tested; post-processing, graph-stage, relationship-confidence, attack-path and exposure checkpoint reuse remain code-tested for exact request/plan/owner, input-digest, size/shape, completed-stage, record-size fallback and TTL binding but were not independently process-kill validated on the emulator. Physical-device, broad accessibility and full-stage recovery gates remain open.
- **Documentation-only commits after the validated implementation:** may update this file/PR prose without changing the validated code claim
- **Last validated device class for this commit:** API 36 `medium_phone` emulator; no physical device was connected
- **Prior device evidence:** current-tree pause/resume and Activity-recreation instrumentation plus external force-stop/reboot restoration now pass on the API 36 emulator; this remains emulator-only and does not establish physical-device acceptance
- **Real-device production validation:** not yet recorded
- **Declarative provider definitions:** **78 authored**
- **Pinned WhatsMyName catalog:** **716 source records; 644 executable HTTPS username rules after parser/policy filtering; 72 non-executable under current policy**
- **Registry-wide live provider validation:** not established
- **Production readiness:** not established

Compilation, unit tests and emulator tests are necessary gates, but they do not substitute for live-provider validation, calibrated identity/face benchmarks, physical-device measurements, accessibility validation or release hardening.

The readiness score is **83/100** after adding deterministic six-state contract fixtures for every catalog definition, encrypted scan-plan summaries, request/plan-bound encrypted public search/image retry payloads with scoped tombstones, bounded encrypted breach-stage, post-processing, graph-stage, relationship-confidence, attack-path and exposure outputs with exact request/plan/owner/digest/TTL/completed-stage binding and fail-closed rerun behavior, terminal provider/pivot projection rejection, exact-only legacy graph provenance, fail-closed logical retirement of unsafe legacy WorkManager status payloads without a forensic erasure claim, reviewed provider-resource/manual remediation states, stricter AI evaluation-fixture provenance checks, encrypted allow-listed stage-output counts, separate authored and pinned WhatsMyName provider-health diagnostics with missing entries retaining their known IDs, exact relationship evidence provenance and canonical endpoint reuse (including Wayback archive assertions), duplicate scanner/plugin relationship merging that preserves all evidence IDs, case-insensitive graph relation-key deduplication that preserves the first serialized edge and unions provenance, stable scanner-produced/Reddit/WhatsMyName relationship evidence IDs, explicit opaque provenance IDs for legacy/external OSINT imports with credential-field rejection, versioned encrypted persistence and restore of bounded canonical relationship assertions, bounded authenticated transient-result collection shapes, coordinator-routed production scan launch/cancellation, exact published-result owner recovery before rerun, no-network provider-maintenance metadata diagnostics with pinned source-catalog integrity and runtime-equivalent WhatsMyName conversion parity, off-main saved-case persistence seams, source-scoped historical/remediation diffing, explicit verified-profile/user-reviewed image-account linkage with bounded history and direct verified-profile media production, source-page-aware reverse-image candidate retention, bounded source-page/image-URL media change history, fail-closed graph reconciliation bounds plus optional persisted-evidence-ledger dangling-reference diagnostics, a named canonical relationship read source, non-finite face-similarity rejection, bounded structured backend/model-hash/pipeline/calibration/quality provenance for face comparisons, held-out/training/authorization provenance gates for entity calibration artifacts, broader focused Compose accessibility semantics and reduced-motion-safe decorative loaders, graph/report accessibility semantics, bounded live evidence corrections that remain local until encrypted save, encrypted Activity-recreation recovery and external emulator force-stop/reboot restoration. The scan still defaults to conservative depth two (hard maximum four), broader automatic verified-account acquisition, universal graph truth, and empirical representative calibration do not yet exist.

The latest validated hardening adds exact fail-closed profile/media correction controls, Pausing-state result recovery before retry, bounded encrypted breach-stage output recovery bound to the immutable request plan, bounded deterministic post-processing, graph-stage, relationship-confidence, attack-path and exposure output reuse bound to exact input digests and request/plan/owner/TTL/completed-stage/shape checks, terminal provider/pivot projection rejection, exact-only legacy graph provenance, exact resolver support/contradiction evidence IDs from a unique persisted ledger, record-size fallback to semantic counts, runtime-equivalent WhatsMyName conversion-parity diagnostics, fail-closed logical retirement of unsafe legacy WorkManager status payloads, exact Wayback relationship provenance, focused accessibility semantics and reduced-motion handling, bounded CaseStore plaintext/envelope sizes with last-good preservation, and a distinct canonical scanner/plugin assertion export sidecar with ShareSafe redaction. These additions improve correctness and export safety but do not close the provider-scale, full-stage recovery, graph-source-of-truth, representative calibration, physical-device, broad accessibility or release-hardening gates below.

The latest validated code commit also keeps authored-provider health and pinned executable WhatsMyName health in separate reports, retains the catalog ID for unvalidated known entries, and merges graph edges by trim/case-insensitive relation key while retaining first-edge text and all explicit evidence IDs. These are bounded correctness/UI improvements; they do not convert the pinned source records into registry definitions or make the graph the sole source of truth.

## Strict 100-point rubric

| Area | Score | Truth |
|---|---:|---|
| Discovery breadth and reliability | **13/15** | Discovery Fabric v2 is integrated into runtime planning and direct-profile execution with 78 declarative definitions, live selector-driven extraction, exact/approved-host redirect checks including HTTPS-downgrade rejection, bounded reads, timeout/retry/cooldown/request-spacing policy, typed truthful outcomes, provider health updates for interactive scans, persisted aggregate health reports with explicit freshness/failure buckets, and separate authored-catalog and pinned-WhatsMyName diagnostics panels that keep Unvalidated distinct from live validation. Aggregate health samples now fail closed as Unavailable when outcome counters are negative/inconsistent or timestamps are in the future, and invalid samples do not inflate observed coverage. A deterministic no-network contract harness now exercises present, absent, soft-error, redirect, challenge and malformed observations for every catalog definition (468 decisions across 78 definitions); it does not promote fixtures to health or evidence. The maintenance audit independently verifies pinned WhatsMyName data/license hashes, byte size, generated rule-ID uniqueness and the exact 716 source / 644 executable / 72 excluded counts with policy-reason diagnostics; it now also proves all 644 executable rows convert to the Kotlin runtime-equivalent category/ID/template/status-marker/request-policy contract with no collision against the 78 authored IDs. These rules remain distinct source records, not additional `ProviderCatalogV2` definitions or live-validated providers. The project remains below the 1,000+ useful reviewed-provider target and lacks registry-wide live contracts. |
| Recursive orchestration | **9/10** | The coordinator/event bridge starts and observes the mature pipeline; production UI launch and cancellation now route through `ScanCoordinatorRuntime`, which binds the selected mode and provider-plan summary before dispatching the durable `ScanSession` path; direct-profile work emits real lifecycle events; live UI consumes bounded event-derived counts; and real terminal scan history is captured. Production background scheduling uses encrypted two-phase request publication, exact UUID/generation ownership, startup `getWorkInfoById` reconciliation, idempotent result-aware success and terminal cancellation observation. Stable direct-profile outcomes and the request-scoped frontier resume by exact scope. A generic depth-driven frontier drain now honors a configured maximum (default two, hard maximum four), shared budgets, verified-only expansion, queued/visited/completed state, conservative admission, bounded rejection diagnostics and durable clear tombstones. Background WorkManager scans now have exact-owner `Pausing`/`Paused` states, terminal cancellation observation, fresh-UUID resume and analysis-surface controls. New requests persist an encrypted plan summary (mode, declarative-plan fingerprint/count and allow-listed stage order), semantic stage ledger, bounded per-stage output counts, request/plan-bound encrypted public search/image payloads with per-request clear tombstones, bounded encrypted breach, graph, relationship-confidence, attack-path and exposure outputs with exact request/plan/owner/digest/completed-stage binding, and sanitized `CheckpointUpdated` events across the major profile/face/breach/graph/scoring/exposure/AI/post-processing boundaries. Terminal snapshots reject late provider/pivot projections for the owned scan while retaining owner-validated checkpoint observability. A worker retry that sees an exact owner with a durably published result now transitions to success before rerunning the pipeline, including a durable `Pausing` state with an exact matching published result. The plan summary is a commitment/metadata record rather than a general parser/frontier loop; other in-flight stage payloads remain absent. Full-stage resume, universal coordinator ownership of mature custom resolvers and full-stage process-death/reboot recovery remain incomplete; the encrypted result path now survives external force-stop/relaunch and emulator reboot with a uiTest-only synthetic fixture. |
| Evidence/provenance | **9/10** | Universal evidence supports provider, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical state. Relationship records now retain explicit evidence IDs, scanner-produced profile/finding edges, Reddit activity edges and WhatsMyName profile-existence edges carry the exact stable evidence IDs created for those observations, duplicate scanner/plugin assertions are merged by normalized endpoint plus trim/case-insensitive relation key while unioning all explicit IDs, and versioned encrypted cases persist the bounded canonical relationship collection through save/load/restore with legacy evidence-ID migration. Legacy endpoint/source matches are migrated only through exact existing evidence values or URLs; graph edges expose positive and contradicting evidence queries. When a relationship endpoint exactly and uniquely matches an already-built evidence entity, the graph reuses that canonical node; ambiguous or unmatched values remain synthetic unresolved nodes rather than fuzzy merges. Wayback `ARCHIVED_AS` assertions now carry the exact snapshot evidence ID, while legacy archive assertions are resolved only on a unique exact match and remain idless when ambiguous. Historical archive attributes now carry explicit semantic kinds while retaining capture timestamps and archive reliability. Legacy/external OSINT, Numverify and interaction imports now receive stable opaque import-digest evidence IDs on their relationship assertions, merge duplicate rows without dropping provenance, and reject credential-bearing fields. Saved-case media history compares bounded canonical source-page/image-URL observations with explicit retrieval/hash/dimension/state provenance, and live finding corrections retain stable evidence IDs while preserving raw evidence until encrypted save. A named read-only canonical relationship source now feeds reconciliation without graph fallback or mutation, and an optional case-level audit now marks canonical/graph IDs dangling when they do not resolve to the non-empty persisted evidence ledger. Older archive/import/other producers still do not populate every field universally, and graph-edge reconciliation remains a diagnostic rather than a single source of truth. |
| Entity resolution | **6/10** | An explainable multi-signal resolver is integrated into the production graph, preserves contributions and contradictions, and prevents same-username-only confirmation. A deterministic digest-bound benchmark now reports confusion-matrix counts, precision, recall, F1, FPR, FNR and unverifiable accuracy. Calibration artifacts fail closed on schema/version/digest/count/policy mismatch; production activation additionally requires explicit held-out split metadata, a distinct training-corpus digest, an authorization-record digest and caller-supplied matches for all three provenance values. Synthetic and regression artifacts cannot activate production policy, and consented artifacts must meet minimum class counts and bind to the exact evaluated corpus. No representative consented artifact or measured production calibration exists, so weights remain engineering parameters. |
| Identity graph | **7/8** | Graph v2 adds semantic node kinds, typed relationships, evidence/contradiction IDs, node states, history fields, queries and schema versioning while preserving legacy case compatibility. Evidence-backed relationship endpoints now reuse one exact unique canonical graph entity where possible; ambiguous values keep an unresolved synthetic fallback. Duplicate edge detection canonicalizes relation keys by trim/case-insensitive comparison while preserving the first serialized spelling and unioning evidence IDs; this prevents formatting drift from creating parallel edges without inferring identity. Archive attributes are attached to historical archive-source nodes with typed relationships, and media candidates/clusters enrich the graph without identity claims. GraphML, declared-attribute GEXF and NetworkX-style node-link JSON serializers provide dependency-free interoperability with Gephi/Cytoscape/NetworkX-style workflows. Case graph exports now keep the projection separate from the read-only canonical scanner/plugin assertion ledger in a distinct CSV sidecar, with ShareSafe endpoint/evidence redaction. Read-only reconciliation diagnostics compare exact endpoint/relation/evidence sets, cap evidence IDs at 256 per diagnostic and fail closed on truncation; when a non-empty case evidence ledger is available, canonical and graph evidence IDs are also checked for dangling references. These diagnostics still do not replace the remaining parallel representations. |
| Image acquisition/correlation | **7/8** | Public candidate acquisition, local SHA-256/aHash/dHash/pHash/histogram/crop comparison, first-class candidate provenance, stable candidate IDs, exact/perceptual duplicate clusters, encrypted case persistence, request/plan-bound retry payloads for public image-index observations, graph enrichment, bounded cross-case fingerprint history, source-page/image-URL keyed change states and inspectable saved-case review UI are implemented. Directly scanned `exists && verified` profile avatars now produce explicit `ImageAccountLinkage` records with `profile:<url>` evidence; reverse-image candidate deduplication keys on canonical image URL plus canonical source-page URL, so distinct verified pages reusing one CDN avatar remain reviewable while exact pairs coalesce deterministically. User-reviewed associations remain supported with bounded evidence/timestamp provenance. These records can add a `USES_AVATAR` edge only for an exact verified-profile association or a user review; visual scores, candidate IDs and clusters never create that edge. Normalization bounds and repairs legacy candidate/cluster references without inventing identity links. Google Lens remains an external/manual reference; broader verified-account acquisition, independent visual review and face validation remain incomplete. |
| Face-correlation validation | **3/6** | YuNet/SFace artifacts are pinned and integrity checked; preprocessing, alignment, quality rejection and calibration tooling exist and its runtime check passes CI. Similarity paths now reject NaN/Infinity inputs and non-finite intermediates/results, with deterministic overflow coverage. Every comparison carries bounded structured backend/model-hash/pipeline/calibration-state and selfie/profile-quality provenance, with explicit reference-policy, measured, imported, fallback and not-run states; legacy payloads default to Unknown. Measured artifacts still fail closed unless they declare identity-disjoint held-out data plus consent/legal-distribution authorization. `face_recognition` and DeepFace are catalogued as alternative ecosystems rather than embedded dependencies because Dossier already has a local consented pipeline. Representative measured FAR/FRR/ROC results and physical-device inference validation do not exist yet. |
| Historical evidence | **6/6** | Exact-URL Wayback recovery, bounded CDX history discovery, selected-snapshot re-fetching, best-effort archive.today fallback, and a bounded fail-soft extractor for explicit historical display name, bio, username, avatar, external links, organization and location metadata now exist. These records retain archive reliability, capture timestamps, historical state and semantic attribute kinds; timeline labels and archive-aware graph relationships keep them distinct from current claims. Saved-case comparison now provides a bounded source-scoped historical/provider change diff with explicit changed, unavailable and not-observed states and UI caveats. Universal extraction across every provider/archive source remains a scope limitation rather than an identity claim. |
| Breach intelligence | **4/5** | HIBP authoritative coverage remains separate from general public exposure; privacy-preserving range flows are used where supported; breach/provider/retrieval/date metadata is preserved. LeakCheck/DeHashed/Breach-Parse/Buster/BreachFinder interoperability is summary-only and rejects credential/secret-bearing records. Coverage remains externally dependent. |
| AI analyst | **5/5** | Generated factual claims must be structured, cite existing evidence IDs and survive deterministic validation; uncited/unknown identifiers are withheld and invalid output falls back locally. The validator also rejects explicit email/URL identifiers not present in the evidence actually cited and now consumes bounded corrected/remediation links on local, remote and deterministic-evaluation paths. A completed remediation outcome is accepted only when its cited evidence is effective and tied to a later successful durable verification scan whose timestamps parse and follow the remediation update. Deterministic evaluation fixtures now fail closed on duplicate/blank IDs, dangling graph endpoints/provenance, oversized graph/evidence/remediation payloads and missing remediation evidence references. Remote graph entities and relationships retain only pseudonymized references to evidence inside the same bounded prompt window, with omitted provenance counted. A production-sized/adversarial evaluation corpus remains a hardening gap, not a source of unverified claims. |
| UX/UI | **7/8** | Overview/Evidence/Connections/Actions remain coherent; live scan state is event-derived; saved Cases support persistent corrections, remediation tracking, reviewed provider-specific/manual/unavailable remediation resources, scan-history display, share-safe export, bounded media provenance persistence, explicit verified/reviewed image-account linkage review and whole-image cluster history review. Live Evidence finding, exactly-resolved profile, and exactly-resolved verified-media linkage cards now expose bounded Confirm/Reject/Unsure/Ignore draft decisions with selected-state semantics and explicit local-only/not-saved wording; ambiguous or generic evidence keys fail closed and raw evidence remains retained until encrypted case save. Background analysis now exposes explicit pause/resume states and truthful checkpoint wording, and durable Activity-recreation recovery redirects back to Analysis when an older navigation route is restored. Graph selection, HUD severity, reverse-video picker, report evidence-source links and draft correction buttons expose explicit Compose semantics, including selected state, dynamic descriptions and `Role.Button`; the latest UI tranche adds heading/state descriptions, descriptive URL roles, 48dp minimum targets for compact controls, and reduced-motion-safe decorative loaders, with focused connected coverage for breach/report/reverse-media semantics. API 36 visual QA fixed consent-footer overlap, machine-token status copy, graph-label clipping, remediation-status overlap, stale scan logs, the background/cancel action collision, ambiguous provider budgets and large-type navigation/count wrapping. The changed scan-budget and navigation states were inspected at 1.0x, 1.3x, 1.5x and 2.0x font scale. Media/provider corrections without a durable evidence key, broad TalkBack/adaptive-layout/physical-device validation and independent review of the new pause/media surfaces remain incomplete. |
| Security/privacy | **3/4** | Keystore AES-256-GCM cases, encrypted request-scoped scan checkpoints and semantic stage ledger, exact request/plan/owner-bound breach summaries and deterministic graph output, generation-bound exact-owner lifecycle state, opaque new WorkManager inputs, allowlisted WorkManager progress/error values, atomic encrypted result replacement, restricted evidence WebView, local visual processing defaults, opt-in remote AI, explicit deletion and pre-write share-safe export are implemented. External report imports and breach/graph checkpoints are bounded, local/in-memory or encrypted, tied to explicit audit seeds and strip/reject password/hash/cookie/token/session/credential material. UI latest-result reads, purge, saved-case listing/deletion, analysis, corrections, remediation and case-save actions now dispatch encrypted work off the main thread; background result envelopes reject oversized nested case/input/graph/media/analysis collection shapes on save and after authenticated load. CaseStore additionally rejects plaintext above 8 MiB and envelopes above 12 MiB, bounds legacy/encrypted reads, validates authenticated sizes before allocation and preserves the last-good file when an oversized save is rejected before atomic replacement. Lower-level synchronous helpers remain for controlled lifecycle paths. Historical plaintext WorkManager rows are not forensically erased, and large-case ANR/storage-corruption testing remains open. |
| Testing/device validation | **4/5** | Provider audit (78 definitions) plus 11 Python maintenance/audit tests with pinned WhatsMyName hash/size/policy diagnostics, deterministic all-definition six-state contract fixtures and runtime-equivalent conversion parity, pinned catalog integrity audit (716 records / 644 executable rules), both debug and uiTest JVM suites (810 tests across 134 result XML files each, 0 failures/errors/skips) including the 9 exposure-checkpoint, 13 terminal-projection and relation-normalization regressions, debug/uiTest APK assembly, Android-test Kotlin compilation, zero-error lint and a complete connected uiTest suite (53 tests, including separate authored/WhatsMyName provider-health diagnostics, 8 WorkManager pause/resume tests, encrypted Activity-recreation recovery, bounded breach-stage behavior, focused post-processing and graph-stage checkpoint/accessibility semantics, exact-only legacy provenance, exact resolver provenance, focused breach/report/reverse-media accessibility semantics, live finding/profile/media correction semantics, exact evidence-key fail-closed behavior, bounded CaseStore save behavior, graph/export redaction semantics, report-evidence semantics, async saved-case persistence, HUD status semantics and reverse-video picker semantics) pass on the exact implementation tree above using the API 36 `medium_phone` emulator. A fresh ADB smoke check on the current APK restored the production encrypted background result after external force-stop/relaunch and after emulator reboot, with the production activity resumed after both launches. Physical Samsung/Pixel/lower-memory, battery/thermal, full-stage recovery and broad accessibility gates remain incomplete. |
| **Total** | **83/100** | Declarative provider extraction, deterministic catalog contract coverage, no-network provider-maintenance metadata validation and runtime-equivalent WhatsMyName conversion parity, surfaced persisted provider-health buckets with invalid-sample rejection, a configured-depth bounded frontier drain with encrypted plan metadata/stage-output counts and request/plan-bound public discovery retry payloads, exact request/plan/owner-bound encrypted breach-stage, post-processing, graph-stage, relationship-confidence, attack-path and exposure checkpoints with fail-closed rerun and record-size fallback, terminal provider/pivot projection rejection, coordinator-routed production launch/cancellation, exact-owner background pause/resume including Pausing/result-ready retry recovery, relationship-level evidence provenance with canonical endpoint reuse and duplicate-assertion evidence-ID preservation, exact-only legacy graph provenance, exact resolver support/contradiction ledger IDs, stable scanner/Reddit/WhatsMyName/import evidence IDs, exact Wayback archive assertion provenance, bounded encrypted relationship-collection persistence and restore, read-only canonical relationship reconciliation with optional persisted-evidence-ledger dangling-reference diagnostics, separate canonical assertion graph-export sidecars with share-safe redaction, evidence-bound remediation verification requiring a later durable scan, reviewed/manual remediation resource states, bounded historical/provider change diffing, bounded graph-provenance redaction, encrypted media persistence/history with source-scoped change states, explicit verified/reviewed image-account linkage with direct verified-profile production and source-page-aware reverse-image candidate retention, exact evidence-keyed finding/profile/verified-media corrections that remain local until explicit encrypted save, fail-closed graph reconciliation bounds, bounded CaseStore reads/writes with last-good preservation, non-finite face-similarity rejection, structured bounded face-comparison provenance, focused heading/role/state-description and reduced-motion-safe UI semantics, report/graph/HUD/reverse-video/draft-correction accessibility semantics, Activity-recreation recovery and external emulator force-stop/reboot restoration, fail-closed transient-result collection-shape validation, strict AI evaluation-fixture provenance checks and off-main UI/case persistence seams are now covered by current-tree tests and builds. This does not establish universal coordinator ownership/full in-flight stage payloads, graph-as-sole-truth migration, registry-wide live validation, broader automatic verified-account acquisition, representative empirical calibration, physical-device acceptance, broad accessibility or release hardening. |

## M0 — Baseline audit

**Status: complete for this branch.** Working evidence, scanner, graph, visual, breach, AI, case, export and Compose architecture was preserved rather than rewritten for package/style purity.

## M1 — Discovery Fabric v2

**Status: partial, production-integrated.**

Implemented:

- typed `ProviderDefinition` schema;
- provider categories, query capabilities, source reliability and bounded request policies;
- declarative existence/extraction rules, now executed by `ProfileScanner` for direct HTTP and bounded WebView paths;
- duplicate/template/priority/request-policy validation;
- deterministic response classification for present, not-found, soft-404, authentication-required, automation-challenged, redirect, unexpected and invalid responses;
- Quick / Standard / Deep / Exhaustive modes backed by actual runtime plans;
- scan-mode persistence in resume markers;
- provider health statistics with persistent diagnostics installed for both background and interactive scans;
- health diagnostics reject negative/inconsistent aggregate counters and future validation timestamps instead of coercing malformed samples into healthy coverage;
- deterministic persisted health assessments for the exact ProviderCatalogV2 and executable WhatsMyName site sets, with explicit Unvalidated/Healthy/Degraded/Unavailable/Stale buckets that cannot inflate provider breadth;
- deterministic no-network present/absent/soft-error/redirect/challenge/malformed contract fixtures covering every catalog definition without promoting fixture outcomes to health or evidence;
- **78 authored provider/service definitions**;
- a separately pinned WhatsMyName catalog from upstream commit `e62338e4fc88536a330733d355a9d33a3a1697c6`, with bundled CC BY-SA 4.0 license/notice and exact data/license SHA-256 checks;
- strict parsing and policy filtering of 716 source records into 644 executable HTTPS username rules and 72 explicitly non-executable records;
- Quick / Standard / Deep / Exhaustive username-rule budgets of 50 / 200 / 500 / 644 per normalized explicit handle, at most three handles, bounded concurrency and response sizes;
- fail-closed catalog-unavailable and provider outcomes, Observed evidence only, and explicit UI separation of direct-profile counts from username-rule budgets;
- direct-profile execution through definitions with bounded response reads, policy timeouts, retries, cooldowns, minimum request spacing, exact/approved-host redirect enforcement and HTTPS-downgrade rejection;
- typed `RateLimited`, `Timeout` and `NetworkUnavailable` outcomes alongside present/not-found/auth/challenge/redirect/parser states;
- provider IDs propagated from templates through candidates/results into evidence;
- stable-scan-ID queued/started/completed/unavailable callbacks and persistent health outcome updates;
- legacy `PLATFORMS` compatibility generated from the v2 registry;
- deterministic provider-registry audit tool and CI, with no-network metadata/schema diagnostics, case-insensitive ID drift checks, URL/placeholder validation and machine-readable JSON output;
- audit parser-drift detection and canonical TRUTH count enforcement;
- sampled advisory provider canaries;
- a public-source capability catalog separating Native, NativeEquivalent, ImportOnly, API-key-dependent, ManualOnly, Retired and Unsupported integration postures;
- explicit truthful states for retired Google/Bing cached-page products rather than simulated support.

Not complete: 1,000+ useful reviewed providers, registry-wide live present/missing/soft-404/redirect/challenge validation (including representative WhatsMyName rules), maintenance workflows beyond the no-network diagnostic tool and surfaced health panel, and migration of every mature custom resolver to the declarative runtime.

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
- scanner-produced profile and finding relationships retain the exact stable evidence IDs created for the corresponding observations; the canonical relationship collection is now persisted in schema-v8 encrypted cases and restored with bounded legacy-ID migration (10,000 relationships / 256 evidence IDs per relationship);
- Reddit public-activity and WhatsMyName username-surface producers now retain their exact emitted evidence IDs on the corresponding relationships;
- real scan lifecycle metadata including start/end, mode, provider-plan size, terminal counts and cancellation state;
- PII-safe SHA-256 seed fingerprint binding for associating a completed scan with an initial explicit case save;
- later correction/remediation edits cannot silently graft a newer scan onto an older case;
- request-scoped encrypted seed, mode, deep-scan and per-scan face-policy restoration through a canonical opaque WorkManager UUID;
- request/plan/candidate-scoped encrypted checkpoints for stable initial direct-profile outcomes, miss-only continuation, deterministic output ordering and no synthetic provider events for restored hits;
- deterministic SHA-256 binding of each encrypted request to the selected declarative provider plan, with a bounded ordered ID summary, an allow-listed mode/stage-order plan summary and explicit backward-compatible uncommitted state for pre-plan records;
- stable allowlisted WorkManager progress/failure values, fail-closed legacy-input handling and exact current-owner result publication;
- exact WorkManager owner/generation binding for an encrypted allow-listed semantic stage ledger, with sanitized `CheckpointUpdated` events reflected in the coordinator snapshot; incomplete stages remain eligible for rerun rather than being falsely reported as resumed output;
- request/plan-bound encrypted public search/image payload envelopes with bounded normalized results, digest summaries, AES-GCM AAD, TTL/tamper checks, per-request clear tombstones and cleanup that cannot delete another request's payload;
- exact request/plan/owner-bound encrypted breach-stage summaries retain only bounded email/breach/public-hit metadata, ordered titles and public URLs; malformed, expired, cross-owner or credential-note payloads are omitted so the breach stage reruns rather than poisoning the request;
- exact request/plan/owner-bound encrypted post-processing checkpoints retain only bounded deterministic analysis JSON plus a SHA-256 commitment to the current profile/evidence/username-observation inputs; malformed, oversized, expired, mismatched or shape-invalid checkpoints are omitted so post-processing reruns;
- exact request/plan/owner-bound encrypted graph-stage checkpoints retain only bounded validated graph JSON plus a SHA-256 commitment to every `EntityGraphBuilder` input; malformed, oversized, expired, mismatched, unsafe or record-too-large payloads are omitted so graph construction reruns while semantic stage counts remain;
- exact request/plan/owner-bound encrypted relationship-confidence checkpoints retain only bounded validated score/reason JSON plus a SHA-256 commitment to the full graph/evidence/username-seed inputs; malformed, oversized, expired, mismatched, unsafe, incomplete-stage or record-too-large payloads are omitted so scoring reruns while semantic stage counts remain;
- exact request/plan/owner-bound encrypted attack-path checkpoints retain only bounded validated path/step JSON plus a SHA-256 commitment to the graph/confidence inputs; malformed, oversized, expired, mismatched, unsafe, incomplete-stage or record-too-large payloads are omitted so path tracing reruns while semantic stage counts remain;
- exact request/plan/owner-bound encrypted exposure checkpoints retain only bounded dimension scores and hashed finding references plus a SHA-256 commitment to every exposure-scoring input; raw finding values, URLs, snippets and remediation text are excluded, and malformed, oversized, expired, mismatched, unsafe, incomplete-stage or record-too-large payloads are omitted so exposure scoring reruns while semantic stage counts remain;
- terminal coordinator snapshots reject late provider/pivot projection events for the owned scan ID while owner-validated checkpoint events remain observable;
- WorkManager progress/output projections fail closed on unexpected legacy keys or value types and mark those rows logically retired without echoing payloads or claiming physical database/WAL erasure;
- atomic old-generation to pending-generation lifecycle replacement, followed by exact prepared-request promotion and fixed-UUID WorkRequest publication;
- generation/owner/request-bound worker claim, result publication before lifecycle success and idempotent retry after a durable-success/WorkManager-commit crash boundary;
- process-startup exact-ID reconciliation that retries unavailable lookups without mutation and can promote/re-enqueue only the persisted pending generation under the same UUID;
- durable cancel intent plus exact WorkInfo terminal observation, with success/failure races routed through result-aware reconciliation;
- process-local worker serialization plus cancellation propagation through the scanner/plugin paths touched by this tranche;
- failed scans route to the analysis/error surface rather than being announced as completed reports.

Supporting lifecycle stores use encrypted request `prepare` / exact-ID load / `promote` / discard operations, durable deletion guards, immutable generation/owner/request records, safe error codes and full-snapshot compare-and-transition operations. Reconciliation distinguishes authoritative missing work from an unavailable lookup and never adopts a unique-work-list result by ordering.

Not complete: true suspended pause/resume, sole coordinator ownership, universal in-flight payload coverage across every stage, a general coordinator-owned parser/frontier loop beyond the persisted plan commitment, migration of remaining custom resolver work to provider lifecycle events and full-stage recovery beyond the encrypted result path. Breach, deterministic post-processing, graph-stage, relationship-confidence, attack-path and exposure slices now have bounded encrypted reuse, but the worker still reruns earlier scan stages before reaching those boundaries. External force-stop/relaunch and emulator-reboot restoration of the result path now pass with a uiTest-only synthetic fixture.

The encrypted resume record stores the seed input, selected mode, deep-scan flag, per-scan strong-face-policy flag, the selected plan fingerprint plus bounded provider IDs for new requests, an allow-listed mode/stage-order plan summary, an allow-listed semantic stage ledger bound to the exact worker owner, bounded per-stage output counts, bounded breach-stage summaries, bounded post-processing analysis checkpoints, bounded graph-stage, relationship-confidence, attack-path and exposure checkpoints and public search/image payload summaries. New WorkManager rows receive only opaque request/generation UUID references. Prepared/current generations, plan metadata, stage metadata/output counts, breach/post-processing/graph/confidence/attack-path/exposure summaries and public search/image envelopes are still request checkpoints rather than a general parser/frontier state or universal in-flight stage payloads. The external smoke check validates only durable result restoration through the production UI with a synthetic fixture; it does not establish full-stage process-death/reboot recovery or physical-device acceptance.

## M3 — Recursive frontier

**Status: partial, integrated with a configured-depth bounded scanner.**

Implemented: admission policy, visited/depth rejection, global and per-signal budgets, common-handle corroboration, weak-signal suppression and verified-only expansion. The production path uses a request-scoped Android Keystore AES-GCM frontier containing queued, visited and completed work plus bounded admission/rejection diagnostics, then drains each configured depth in order (default two, hard maximum four). State is saved before execution and after each completed result; thrown or cancelled work remains pending. Storage enforces request-scoped AAD, bounded envelopes, atomic fsynced replacement, path/symlink containment and durable clear tombstones that prevent a late worker from recreating purged state.

Not complete: one coordinator-owned general parser/frontier plan covering every scan payload, checkpointed in-flight payloads for all stages, cross-process file locking, eager expired-file pruning and full-stage process-kill/reboot acceptance. Public search/image envelopes now persist bounded retry data, while the persisted plan summary records mode, catalog commitment, counts and stage order, the semantic ledger records completed major-stage counts (including breach/AI boundaries), and graph construction can retain one bounded deterministic output; these still do not establish universal pause/resume semantics. The encrypted result path survives external force-stop/relaunch and emulator reboot with a uiTest-only synthetic fixture.

## Evidence and provenance

The universal `Evidence` model remains the canonical evidence layer. It can retain provider ID, URL, evidence kind, retrieval/observation timestamps, verification state, reliability, content SHA-256, parser version, historical/current state and confidence.

**Invariant:** a high numeric confidence value does not automatically become `Verified`.

**Import invariant:** a third-party scanner/export never becomes `Verified` simply because it reported a match.

Remaining gap: older producers do not yet populate every provenance field consistently.

## M4 — Identity Graph v2

**Status: partial, production-integrated.**

Implemented: full semantic `GraphEntityKind`, stable legacy type compatibility, typed relationship taxonomy, node state, edge evidence IDs, contradiction IDs, historical flags, first/last observations, graph schema version, relationship/history/conflict queries, exact-only legacy relationship provenance migration, evidence-backed edge/entity queries and standard graph serialization.

Not complete: graph-only truth across every subsystem, richer graph-history review, and full archive/image/breach population at contract depth. Case-level reconciliation now optionally checks non-empty persisted evidence ledgers for dangling canonical/graph IDs, but remains diagnostic and does not replace the remaining parallel representations. Legacy relationships without an exact endpoint/source evidence match remain intentionally unlinked rather than inferred.

## M5 — Entity Resolver v2

**Status: partial, integrated with deterministic evaluation but not representative calibration.**

Implemented: explainable contributions, independent URL/verification/cross-link/email/name/organization/location signals, contradiction handling, conservative confidence bands and regressions proving a shared username/name alone cannot confirm identity. A deterministic synthetic/consented benchmark reports TP/FP/FN/TN, correct/unsafe unverifiable counts, precision, recall, F1, FPR, FNR and unverifiable accuracy over a digest that includes every resolver-relevant field and finding. A strict JSON calibration artifact validates schema, version, digest, counts and policy; synthetic artifacts cannot activate production policy, while consented artifacts require at least 100 positive and 100 negative cases and exact evaluated-corpus binding.

Not complete: a representative consented benchmark, published measured production metrics and empirically fitted weights/thresholds. The bundled synthetic cases prove harness behavior only, and an artifact's consent declaration is not independent proof of consent.

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
- encrypted case persistence for bounded image results, candidate provenance and duplicate clusters;
- case-load normalization that caps media history, deduplicates identifiers, removes orphan clusters and strips dangling/mislinked references;
- graph enrichment that links image candidates/clusters to matching public account pages without making identity claims;
- explicit UI wording that whole-image duplicate/repost similarity does not identify a person;
- generic Dossier HTTP identity rather than browser/device impersonation.

Implemented in this tranche: bounded cross-case/cross-scan fingerprint grouping with per-case observations, retained source-page/provider/retrieval/hash provenance, source-page/image-URL keyed added/changed/unchanged/not-observed/unavailable states, case-local handling when fingerprints are absent, accessible saved-case review wording, explicit `ImageAccountLinkage` records, direct production propagation from `exists && verified` profile-avatar results, and source-page-aware reverse-image candidate retention that preserves distinct verified pages reusing one avatar URL while coalescing exact image+source pairs. A `USES_AVATAR` graph edge is admitted only for an exact verified-profile association or a user-reviewed association with bounded evidence IDs/timestamps; visual scores and duplicate clusters never create it. Not complete: broader automatic verified-account acquisition/correlation and independent visual acceptance. Face similarity remains a separate supporting signal.

## M7 — Face validation

**Status: partial.**

Implemented: pinned OpenCV Zoo YuNet/SFace models, exact size/SHA verification, atomic installation, deterministic preprocessing/alignment, five landmarks, quality/ambiguity rejection, cosine similarity and calibration tooling with identity-disjoint held-out support.

Not complete: adequate legal/consented benchmark, measured ROC/FAR/FRR, demographic/device/pose/age evaluation and representative physical-device latency/thermal/battery validation. Reference thresholds remain engineering values until measured.

## M8 — Historical identity

**Status: partial.**

Implemented: bounded exact-URL Wayback lookup, CDX history discovery for explicit profile/personal-site URLs, bounded direct snapshot re-fetch, best-effort archive.today fallback, archive-only verification, historical confidence ceilings, current/historical labeling and timeline construction from real timestamps only. Directly re-fetched Wayback HTML now has a fail-soft bounded extractor for explicit display name, bio, username, avatar URL, external links, organization and location attributes; each attribute retains the capture timestamp, archive reliability, historical flag and semantic attribute kind. Archive attributes are represented as historical source-node relationships in the graph rather than current ownership claims.

Implemented in this tranche: saved-case comparison now groups archive replay URLs by canonical target and exposes bounded ADDED/CHANGED/UNCHANGED/UNAVAILABLE/NOT_OBSERVED_IN_LATEST_CASE states in the UI without treating absence as deletion. Not complete: equivalent structured extraction across every archive/provider source and universal archive timestamp propagation.

## M9 — Breach intelligence

**Status: substantially implemented, externally dependent.**

Implemented: Pwned Passwords range lookup, authenticated privacy-preserving email range lookup where supported, no full-address silent fallback, authoritative/public-web separation, explicit unavailable/configuration states and breach/provider/date/retrieval/data-class provenance. Completed background breach-stage output can be resumed from an encrypted request/plan/owner-bound summary containing no response bodies or credential material; invalid summaries fail closed and trigger a fresh breach check.

Third-party breach-tool interoperability is intentionally summary-only. Credential material is not an import target.

## M10 — Evidence-grounded AI

**Status: partial, production-integrated.**

Implemented: deterministic structured evidence snapshot, structured claim/result contract, evidence-ID validation, uncited factual-claim rejection, unsupported email/URL identifier rejection, contradiction downgrade, output bounds, raw-prose rejection and deterministic local fallback. Remote graph entities and relationships retain bounded pseudonymized references to evidence and contradicting evidence from the same remote evidence window; raw local IDs remain excluded.

Implemented in this tranche: local, remote and deterministic evaluation paths pass bounded remediation links to the validator; completed removal language requires effective cited evidence plus a later durable verification scan whose timestamps parse and follow the remediation update. Not complete: production-sized/adversarial evaluation fixtures and evaluation of the bounded remote provenance contract under large graph inputs.

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
- bounded saved-case whole-image cluster history with exact/perceptual fingerprint grouping and explicit non-identity wording;
- explicit verified-profile/user-reviewed image-account linkage review with bounded evidence/timestamp provenance; visual similarity never creates the identity edge;
- reverse-video source picking now exposes a truthful selected-file/empty-state description and button role;
- background-analysis pause/resume controls that surface durable `Pausing`/`Paused` state and exact checkpoint retention;
- provider-catalog health diagnostics with explicit Healthy/Degraded/Unavailable/Stale/Unvalidated buckets, bounded nonhealthy preview and no-live-validation caveat;
- user-visible local import controls for legacy Twint/snscrape plus broader external OSINT reports;
- human-readable presence/profile status copy without changing persisted domain enum tokens;
- measured/clamped graph labels that preserve complete short identifiers and ellipsize only when the canvas cannot fit them;
- remediation action/value/status rows that remain separate when action copy wraps;
- HUD status severity is exposed through Compose `stateDescription` semantics for positive, warning, critical and informational levels, with connected instrumentation coverage;
- focused accessibility hardening adds heading semantics, descriptive URL/button roles, selected/expanded state descriptions, 48dp minimum targets for compact controls and reduced-motion-safe decorative loaders; connected coverage now exercises breach, report and reverse-media semantics;
- a deterministic uiTest-only `.test` fixture that exercises the production encrypted case/transient-result stores and is absent from the debug manifest.

Visual QA on the API 36 Medium Phone emulator produced and inspected 15 canonical screenshots covering consent, identity validation, username review, scan configuration/progress, analysis, all report tabs, saved cases, corrections and remediation. The accepted captures fixed consent copy under the sticky CTA, the raw `SuspiciousSimilarity` token, the clipped `jane@example.tes` graph label, remediation-status collision, stale scan-complete log, the Continue-in-background/Cancel overlap and ambiguous scan-budget presentation. `06-scan-configuration.png` now separates direct-profile counts from pinned HTTPS username-rule budgets; `07-scan-progress.png` shows unobstructed logs, separate action bounds and real scheduled/completed/unavailable counter semantics. The changed scan-budget cards and bottom navigation were inspected at 1.0x, 1.3x, 1.5x and 2.0x font scale; count competition and navigation-label ellipsis found during QA were fixed before acceptance. The provider-progress values are a deterministic uiTest-only lifecycle fixture and are not claimed as a live-network scan. Screenshots contain synthetic/non-personal test data only. This remains one viewport and affected-state scaling, not TalkBack, reduced-motion, landscape/tablet, whole-product accessibility or physical-device acceptance.

Not complete: production timeline, correction controls beyond live Finding/profile cards and exactly-keyed verified-media linkages, mature tablet/landscape adaptation, localization/RTL, full TalkBack/switch/keyboard validation and complete accessibility validation of the new pause/media/provider-health surfaces.

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
- explicit recheck linkage to the current evidence ID and only the latest new successful scan ID; failed/cancelled newer scans remain `NotRechecked`;
- user-visible wording that disappearance from one newer scan is not proof that search indexes, archives or every live copy are gone.

Not complete: broad provider-specific removal/deletion/correction links and explicit live/search-index/archive three-way removal verification. Automatic linkage is bounded to exact evidence IDs or full type/value/canonical-source finding keys and a newer successful scan; it does not claim global deletion.

## Reports and export

Full export provides PDF + JSON evidence packages with per-section SHA-256 hashes and a manifest hash. Hashes are integrity metadata, not independent attestation.

`ShareSafe` redaction is exposed from saved Cases and runs **before files are written**. It removes/generalizes identifying evidence and warns that redaction reduces disclosure but cannot guarantee anonymity.

Graph interoperability serializers now exist for GraphML, GEXF and node-link JSON and are exposed from report/saved-case actions through share-safe redaction. They still need independent visual review and broader export acceptance. Any future external graph share must pass through the same redaction policy.

## Security and privacy

Implemented controls include Keystore-backed AES-256-GCM cases, versioned/atomic encrypted storage, opaque WorkManager request references, allowlisted background progress/error values, explicit deletion, restricted host-bound/cookie-free/non-impersonating evidence WebView rendering, system Photo Picker, local visual/face processing, opt-in remote AI, pre-write share-safe redaction, PII-safe scan-history binding and non-impersonating public-source HTTP identities.

The resume record is encrypted with AES-256-GCM in AndroidKeyStore. It stores the complete identity input, scan mode, deep-scan flag and per-scan face-policy flag behind UUID-named ciphertext files plus an opaque UUID pointer, authenticates the format/request ID with AAD, enforces a 24-hour TTL and bounded reads, and uses temporary-file publication with file and parent-directory fsync, rollback and a process-local lock. Key creation is fail-closed when prior encrypted state exists. Legacy plaintext UI markers migrate through a bounded validation/encryption/deletion policy, and typed internal read/write states preserve invalid, missing, expired and storage-failure distinctions.

New `BackgroundScanWorker` requests place only canonical opaque request/generation UUIDs in WorkManager input `Data`; output and relayed progress are bounded to fixed stage/error codes. The API 36 WorkManager tests inspect the current WorkSpec row and confirm the seeded identity value is absent. Unexpected legacy keys or value types in observable progress/output rows are now fail-closed and logically retired from the UI projection without echoing their values. These tests do not inspect retained historical rows, SQLite WAL/free pages or prove forensic erasure. Pre-upgrade WorkManager rows may retain raw legacy `identity_json`/flags until WorkManager prunes them; the upgraded worker rejects those inputs without decoding or echoing their values.

Lifecycle hardening is production-integrated but not release-complete. Scheduling atomically publishes the replacement generation before cancelling the old exact UUID, startup reconciles only the persisted exact WorkManager UUID, result publication precedes durable success, retries are idempotent for an exact matching result and cancellation remains requested until the exact row is terminal. Initial direct-profile stable results use AES-GCM with request/plan/candidate AAD, bounded reads, atomic fsynced replacement, durable request tombstones and stable-only reuse. The bounded pivot frontier also uses Android Keystore AES-GCM, request-scoped AAD, bounded envelopes, atomic fsynced replacement and durable clear tombstones. Public search/image retry payloads use a separate request/plan/stage-bound AES-GCM envelope with bounded normalized records, TTL/tamper validation, scoped tombstones and explicit per-request cleanup. The plan summary and semantic stage ledger use the same encrypted request record, exact owner/generation comparisons and atomic fsynced replacement; they store only allow-listed mode/fingerprint/stage metadata plus bounded non-sensitive item/verified/omitted counts for completed major stages, while breach, post-processing, graph, relationship-confidence, attack-path and exposure outputs are retained only when their strict authenticated bounds fit. Exposure checkpoints exclude raw finding values and rebuild sensitive top-finding projections only from current in-memory findings after digest validation. Terminal provider/pivot projections are rejected after completion. Background result envelopes now reject oversized/non-regular files, malformed metadata, invalid IV/ciphertext sizes and oversized plaintext before allocation/decryption. AI provider configuration now migrates legacy plaintext keys into encrypted storage and fails closed on unusable ciphertext; it does not expose a plaintext fallback. Production scan launch/cancellation routes through the coordinator, and saved-case listing/deletion, analysis, corrections, remediation and case-save UI paths use IO-dispatched seams; lower-level synchronous helpers remain for controlled lifecycle paths. An external ADB force-stop/relaunch and emulator-reboot smoke check restored the encrypted result through the production UI using a uiTest-only synthetic fixture. Remaining M2/M13 risks include full-stage recovery beyond the result path, a crash/failed-cleanup window that can retain a prior encrypted profile scope until explicit purge or later maintenance, a wall-clock assumption in mismatched-result retirement, large-case ANR/storage-corruption coverage and universal in-flight payload coverage.

External OSINT imports are local/in-memory and bounded. Import parsers require explicit audit seeds appropriate to the source family and strip or reject secret-bearing fields. No newly introduced challenge bypass, credential acquisition, private-source access, hidden tracking, Tor person-crawling, authenticated social scraping or traffic-evasion behavior is permitted.

## Validation record

Validated implementation commit:

```text
37f967d
```

This is the separate pinned WhatsMyName health-diagnostics and case-insensitive graph relation-key tranche; the validated tree also includes `ae7bfc8` bounded encrypted exposure checkpointing and terminal projection guards, `82568cd` attack-path checkpointing, `d16c740` exact legacy enrichment and evidence-derived IDs, `1012ba3` completed-stage relationship-confidence hardening, `a80a58a` confidence output reuse, `985dd5e` exact resolver support/contradiction ledger IDs, `84f07d8` graph-stage checkpointing, `bc623f0` runtime-equivalent WhatsMyName conversion-parity maintenance validation, `7fd49df` verified-profile reverse-image source-page retention, `67742db` case-level dangling evidence-reference diagnostics, the `0cbc2ca` post-processing checkpoint tamper regression, `c404592` bounded deterministic post-processing output reuse, `acc325a` legacy WorkManager status-payload retirement, the `1764233` request-plan-bound breach-stage resume follow-up, the `9bc1cd3` encrypted breach summary checkpoint, `a810d0c` focused accessibility/reduced-motion hardening, `4ee7e14` exact relationship provenance migration, the `674fe2b` evidence-keyed media correction tranche, the `0bd65dd` Pausing-state result recovery fix, canonical graph-assertion export separation, exact profile-evidence correction controls, bounded encrypted CaseStore persistence, held-out calibration provenance propagation, exact published-result owner recovery, pinned source-catalog maintenance diagnostics, bounded live evidence corrections, source-scoped media change history, a read-only canonical relationship source, structured face-comparison provenance, bounded graph-reconciliation diagnostics, fail-closed face-similarity math, Activity-recreation recovery, explicit import provenance, direct verified-profile media production and bounded encrypted relationship save/restore migration.

Validation date:

```text
2026-08-25
```

Current-session validation (the uiTest APK includes the pre-existing uncommitted fixture edit; the implementation commit itself does not):

```text
Provider registry audit       PASS — 78 definitions / 0 errors
Provider maintenance tests    PASS — 11 no-network schema/conversion-parity fixtures / 0 failures
Provider contract fixtures    PASS — 468 deterministic six-state decisions / 78 definitions / no network
WhatsMyName integrity audit   PASS — 716 records / 644 executable rules / pinned hashes match
WhatsMyName conversion parity PASS — 644/644 runtime-equivalent rules / 0 conversion errors / 0 primary-ID collisions
Debug JVM unit tests          PASS — 810 tests / 134 result XML files / 0 failures / 0 errors / 0 skips
uiTest JVM unit tests         PASS — 810 tests / 134 result XML files / 0 failures / 0 errors / 0 skips
Android-test Kotlin compile  PASS — `compileUiTestAndroidTestKotlin`
Debug APK                     PASS — 115,614,184 bytes / SHA-256 81A3B20B4CDC615E4CA110C6F0F8A91CD3FBAF317D97199E04E997C3EE4B7DE8
uiTest APK                    PASS — 243,145,145 bytes / SHA-256 8D0CDC3EA402DA2E6B09031F0E112B1889CBDCDE64448F8104B344FF05B8AC7D
Android-test APK              PASS — 1,027,632 bytes / SHA-256 CBFB4697EDC1C1A466C097B6D39715C3F94D03FB02EEB917FAF6285A2AFED1C7
Debug lint                    PASS — 0 errors / 69 warnings
uiTest lint                   PASS — 0 errors / 72 warnings
Connected uiTest suite         PASS — 53 tests / 0 failures on API 36 `medium_phone` emulator (provider-health panel, 8 WorkManager pause/resume tests, Activity-recreation recovery, bounded breach-stage checkpoint behavior, focused post-processing checkpoint/accessibility semantics, focused breach/report/reverse-media accessibility semantics, live finding/profile/media correction semantics, exact evidence-key fail-closed behavior, bounded CaseStore save behavior, graph/export redaction semantics, report-evidence semantics, async saved-case persistence, HUD status semantics and reverse-video picker semantics included)
External recovery smoke        PASS — production encrypted result restored and remained visible after fresh ADB force-stop/relaunch and emulator reboot; production `MainActivity` resumed after both launches using the uiTest-only synthetic fixture
Graph evidence-ledger audit    PASS — 11 focused JVM tests; dangling canonical/graph evidence IDs fail closed when a non-empty ledger is supplied, legacy empty-ledger cases remain fail-soft
Reverse-image source dedupe    PASS — 14 focused JVM tests across candidate retention/linkage/evidence-ID paths; distinct source pages reusing one avatar URL remain separate, exact image+source pairs coalesce
Post-processing checkpoint     PASS — 7 focused JVM tests covering round-trip/shape bounds, input-digest invalidation, encrypted request/owner rebind, mismatch rejection, TTL expiry and authenticated tamper rejection; no independent process-kill claim
Graph-stage checkpoint         PASS — 6 focused JVM tests covering bounded graph codec/digest behavior, dangling/unsafe/oversized rejection, encrypted request/owner rebind, pre-completion and binding mismatch rejection, authenticated tamper rejection, TTL and record-size fallback; no independent process-kill claim
Relationship-confidence checkpoint PASS — 8 focused JVM tests covering bounded score/reason codec, unsafe/duplicate/oversized rejection, full-input digest invalidation, encrypted request/owner rebind, mismatch rejection, completed-stage marker enforcement, TTL and authenticated tamper rejection; no independent process-kill claim
Attack-path checkpoint        PASS — 7 focused JVM tests covering bounded path/step codec, unsafe/duplicate/oversized rejection, graph/confidence digest invalidation, encrypted request/owner rebind, mismatch rejection, completed-stage marker enforcement and TTL/tamper rejection; no independent process-kill claim
Exposure checkpoint           PASS — 9 focused JVM tests covering bounded redacted dimension/top-finding codec, raw-value exclusion, finding/breach digest invalidation, rebuild mismatch rejection, encrypted owner rebind, TTL/tamper/binding/incomplete-stage rejection and missing completed-stage marker rejection; no independent process-kill claim
Terminal projection guard     PASS — 13 coordinator-runtime tests, including 2 terminal/stale-provider-pivot acceptance invariants; no physical-device claim
Catalog-health separation    PASS — 5 UsernameDiscoveryScreen + 7 ProviderHealthAssessment JVM tests; authored and pinned reports stay separate and unvalidated known IDs remain stable
Graph relation normalization PASS — 10 EntityGraphBuilder + 6 GraphSchemaV2 + 11 GraphEvidenceReconciliation + 3 CaseEvidenceIdMigration JVM tests; case/whitespace variants merge without dropping provenance
Visual QA                     NOT RUN in this validation session
```

The latest focused additions keep the pinned executable-rule health report separate from the 78 authored-provider report and normalize graph relation keys by trim/case-insensitive comparison without rewriting the first persisted relation spelling or dropping evidence IDs.

The deterministic JVM/build gate was run with the declarative extractor, provider-health buckets/panel summary including invalid aggregate-sample rejection, six-state provider-contract fixtures, no-network provider-maintenance metadata and runtime-equivalent conversion-parity fixtures plus pinned source-catalog hash/size/exclusion diagnostics, redirect-policy, WebView-policy, frontier-depth, coordinator launch-plan binding, terminal provider/pivot projection invariants, exact published-result retry recovery including the Pausing result-ready race, encrypted breach-stage checkpoint round trips/binding/size/credential-note rejection and deterministic finding reconstruction, bounded post-processing checkpoint codec round trips/input-digest invalidation/request-owner rebind/mismatch rejection/TTL expiry, bounded graph-stage, relationship-confidence, attack-path and exposure codec/digest/shape/store/owner/rebind/tamper/TTL/completed-stage/record-size fallback tests, fail-closed legacy WorkManager key/value handling, graph evidence-ledger dangling-reference tests, exact-only legacy provenance and evidence-derived ID tests, exact resolver support/contradiction provenance tests, reverse-image source-page-aware candidate retention/deduplication tests, AI-key, held-out calibration provenance and mismatch gates, encrypted-plan-summary matching, encrypted public discovery payload round-trip/plan-mismatch/tamper/TTL/scoped-clear checks, encrypted stage-ledger owner binding/output round-trip, remote graph-provenance, encrypted result-store bounds including nested case/graph/media/analysis/relationship shape rejection on save and authenticated load, bounded CaseStore exact-save/read rejection and last-good preservation, pause/resume lifecycle, media-history, source-scoped media-change states, media-account-linkage normalization/history, verified-profile media linkage, exact media-candidate evidence-key resolution, face-calibration attestation, non-finite similarity rejection and structured face-provenance round trips, bounded draft/finding/profile/media-correction persistence/replacement, off-main persistence including saved-case async seams, historical/provider change diff, canonical graph endpoint reuse, read-only canonical relationship reconciliation, duplicate relationship provenance merge, scanner/Reddit/WhatsMyName/import evidence-ID provenance, exact Wayback relationship provenance, versioned relationship save/restore/migration bounds, canonical graph/assertion export separation and ShareSafe redaction, remediation-resource/recheck linkage and evidence-bound AI tests including later durable scan timestamp binding. The complete connected uiTest suite then ran on the API 36 `medium_phone` emulator; its 53 tests covered consent/UI smoke flows, encrypted case/case-comparison behavior, timeline/history, saved-case AI, provider-health diagnostics, WorkManager ownership, Activity-recreation recovery, bounded breach-stage behavior, focused post-processing and graph-stage checkpoint/accessibility semantics, focused breach/report/reverse-media accessibility semantics, live finding/profile/media correction semantics, exact evidence-key fail-closed behavior, bounded CaseStore save behavior, graph/export redaction semantics, report-evidence semantics, the delayed exact-owner pause/resume lifecycle, async saved-case persistence, HUD status semantics and reverse-video picker semantics. A fresh ADB smoke check on the current APK additionally seeded the production encrypted result store through the uiTest-only receiver and verified the encrypted result remained visible after external force-stop/relaunch and after emulator reboot, with the production `MainActivity` resumed after both launches. The uiTest-only receiver edit that was already present in the worktree remains uncommitted; the APK hashes above therefore describe current-worktree artifacts, not a clean checkout of the implementation commit. No physical-device, TalkBack, switch/keyboard, battery/thermal or full-stage recovery claim is made here.

## Current production blockers

1. Grow Discovery Fabric beyond 78 authored definitions and the separate 716-record/644-executable-rule pinned username catalog toward the contract's 1,000+ useful reviewed providers, with automated maintenance/import and representative live provider-contract validation; pinned source-catalog hash/size/policy diagnostics now fail closed but do not add provider breadth.
2. Migrate remaining mature custom resolvers to the v2 runtime and extend provider-health diagnostics into maintenance workflows; production UI launch/cancellation now routes through the coordinator, but current health buckets are not registry-wide live validation.
3. Extend the persisted plan commitment into a coordinator-owned general parser/frontier plan with in-flight payloads and surfaced diagnostics for every stage beyond the current public search/image retry envelopes. Exact published-result owner recovery now prevents an immediate retry from rerunning a durably published result, including the exact Pausing/result-ready race; bounded deterministic checkpoints now cover exposure scoring as well as later graph/confidence/path stages; and external force-stop/relaunch plus emulator-reboot restoration passes with the production UI and a synthetic fixture, but this does not prove full-stage worker recovery or a general coordinator pause/resume contract.
4. Finish removing lower-level synchronous encrypted-result helpers from non-lifecycle call paths and run large-case ANR/storage-corruption tests; saved-case UI persistence is now IO-dispatched and result-envelope size bounds are enforced.
5. Calibrate entity resolution with a representative benchmark and publish precision/recall/FPR/FNR/calibration results here; activation now fails closed unless a held-out split, distinct training digest, authorization-record digest and caller-supplied provenance matches are all present.
6. Finish universal evidence/provenance population and graph-as-sole-truth migration; scanner, Reddit and WhatsMyName edges now preserve exact evidence IDs, the canonical relationship collection is durably persisted, and graph exports keep it separate from the derived projection, but legacy/import/archive and other producers still need migration and graph-edge reconciliation remains separate.
7. Broaden automatic verified-account image acquisition/correlation and independent visual acceptance; directly scanned verified-profile avatars, explicit user-reviewed `USES_AVATAR` linkages and bounded source-page/image-URL cross-scan change history are now implemented without treating visual similarity as proof.
8. Run the face-correlation benchmark and publish measured ROC/FAR/FRR thresholds, then validate on representative physical devices.
9. Extend historical attribute extraction beyond directly re-fetched Wayback HTML and universalize archive timestamp propagation; source-scoped change-diff UX is now implemented.
10. Build a production-sized AI evaluation corpus and adversarial large-graph remote provenance tests; bounded corrected/remediation-native validation and fixture-provenance integrity checks are now wired on all model paths.
11. Extend correction controls beyond live Finding, exactly-resolved profile cards, and exactly-resolved verified-profile media linkages, including provider surfaces that lack a durable evidence key today, and broaden reviewed provider-specific remediation/recheck resources beyond the current explicit allowlist/manual fallback; bounded exact-key recheck linkage, catalog schema validation and graph export/share actions now exist but still need broader acceptance review.
12. Perform physical Samsung/Pixel/lower-memory QA, full-stage process-death/background recovery, font/display-scale, TalkBack/switch/keyboard, battery/network/thermal and large-case performance testing; the API 36 `medium_phone` connected suite now passes 53 tests, and an external force-stop/relaunch plus emulator-reboot smoke check restores the encrypted result, but all evidence remains emulator-only and fixture-seeded.
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
