# Dossier — Implementation Truth

This is the sole live implementation/readiness record for Dossier. `AGENTS.md` defines the target; this file describes reality.

## Current snapshot

- **Strict product readiness score:** **63/100**
- **Milestone state:** M0 complete; M1–M6 and M8–M12 materially advanced but still partial; M7 and M13 remain partial
- **Current implementation branch:** `feat/product-contract-discovery-v2`
- **Last validated implementation commit:** `ead2e9806914ee0bd525a2c9d0a548c17348f5fa`
- **Validated CI on that commit:** provider-registry audit, face-calibration runtime check, JVM unit tests, debug APK assembly, and all **9** API 35 Compose instrumentation tests
- **Last validated device class:** Pixel 6 profile, Android API 35, x86_64 emulator
- **Real-device production validation:** not yet recorded
- **Declarative provider definitions:** **78 authored**
- **Registry-wide live provider validation:** not established
- **Production readiness:** not established

Compilation, unit tests and emulator tests are necessary gates, but they do not substitute for live-provider validation, calibrated identity/face benchmarks, physical-device measurements, accessibility validation or release hardening.

## Strict 100-point rubric

| Area | Score | Truth |
|---|---:|---|
| Discovery breadth and reliability | **9/15** | Discovery Fabric v2 is integrated into runtime scan planning with 78 declarative definitions, categories, validation, health primitives, scan modes, maintenance-audit CI and compatibility execution. It remains far below the 1,000+ reviewed target and lacks registry-wide live validation and universal v2 scheduling. |
| Recursive orchestration | **5/10** | The coordinator/event bridge starts and observes the mature pipeline; live UI consumes real event-derived state; two-hop discovery has admission/visited/depth/budget rules; real terminal scan history is captured. True in-flight pause/resume, sole coordinator ownership, per-provider lifecycle events and a general persisted frontier remain incomplete. |
| Evidence/provenance | **6/10** | Universal evidence supports provider, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical state. Verification is separate from numeric confidence. Older producers still do not populate every field universally. |
| Entity resolution | **5/10** | An explainable multi-signal resolver is integrated into the production graph, preserves contributions and contradictions, and prevents same-username-only confirmation. Weights remain engineering parameters rather than benchmark-calibrated probabilities. |
| Identity graph | **6/8** | Graph v2 adds semantic node kinds, typed relationships, evidence/contradiction IDs, node states, history fields, queries and schema versioning while preserving legacy case compatibility. Some subsystems still retain parallel representations and not every edge has complete evidence linkage. |
| Image acquisition/correlation | **6/8** | Public candidate acquisition, local SHA-256/aHash/dHash/pHash/histogram/crop comparison, first-class candidate provenance, stable candidate IDs, exact/perceptual duplicate clusters and inspectable Reverse Media provenance UI are implemented. Candidate/cluster persistence into encrypted cases and the main identity graph remains incomplete. |
| Face-correlation validation | **3/6** | YuNet/SFace artifacts are pinned and integrity checked; preprocessing, alignment, quality rejection and calibration tooling exist and its runtime check passes CI. Representative measured FAR/FRR/ROC results and physical-device inference validation do not exist yet. |
| Historical evidence | **3/6** | Exact-URL Wayback recovery exists and a timestamp-disciplined timeline builder separates current, historical and breach events without inventing dates. Universal archive metadata propagation, broader extraction and timeline UI remain incomplete. |
| Breach intelligence | **4/5** | HIBP authoritative coverage remains separate from general public exposure; privacy-preserving range flows are used where supported; breach/provider/retrieval/date metadata is preserved. Coverage still depends on compatible user credentials/provider availability and full investigation UX is unfinished. |
| AI analyst | **4/5** | Generated factual claims must be structured, cite existing evidence IDs and survive deterministic validation; hallucinated/uncited claims are withheld and invalid output falls back locally. Corrected-graph/remediation-native inputs and a production evaluation corpus remain incomplete. |
| UX/UI | **6/8** | Overview/Evidence/Connections/Actions remain coherent; live scan state is event-derived; saved Cases support persistent corrections, remediation tracking, real scan-history display, non-overclaiming remediation rechecks and share-safe export; Reverse Media exposes candidate provenance/clusters. Timeline UX, case-integrated image clusters, live Evidence corrections and broad accessibility/adaptive-layout validation remain incomplete. |
| Security/privacy | **3/4** | Keystore AES-256-GCM cases, restricted evidence WebView, local visual processing defaults, opt-in remote AI, explicit deletion, pre-write share-safe export redaction and a non-browser-impersonating reverse-image User-Agent are implemented. The full security/privacy test matrix and finer redaction controls remain incomplete. |
| Testing/device validation | **3/5** | Provider audit, calibration runtime, JVM tests, APK assembly and 9 Compose tests pass on API 35, including encrypted scan-history/correction/remediation persistence and non-overclaiming remediation verification. Physical Samsung/Pixel/lower-memory, battery, process-death, font-scale and accessibility gates remain incomplete. |
| **Total** | **63/100** | A materially integrated contract tranche is validated. Empirical calibration, provider scale and production hardening still cap readiness. |

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
- legacy `PLATFORMS` compatibility generated from the v2 registry;
- deterministic provider-registry audit tool and CI;
- audit parser-drift detection and canonical TRUTH count enforcement;
- sampled advisory provider canaries.

Not complete: 1,000+ useful reviewed sources, registry-wide live present/missing/soft-404/redirect/challenge validation, universal v2 per-provider rate/concurrency/cooldown scheduling, persisted provider-health history, and migration of every mature custom resolver.

## M2 — Scan Coordinator + live events

**Status: partial, production-integrated.**

Implemented:

- `ScanId`, `ScanRequest`, `ScanRunState`, `ScanEvent`, `LiveScanSnapshot`;
- coordinator start/cancel wrapper around the mature `ScanSession`;
- real stage/profile/face/breach/graph/analysis/completion/cancellation observations;
- production live UI counters from real session state;
- no invented provider-completion events;
- real scan lifecycle metadata including start/end, mode, provider-plan size, terminal counts and cancellation state;
- PII-safe SHA-256 seed fingerprint binding for associating a completed scan with an initial explicit case save;
- later correction/remediation edits cannot silently graft a newer scan onto an older case.

Not complete: true suspended pause/resume, provider queued/started/completed/unavailable events, sole coordinator ownership, persisted event checkpoints and crash-recovery state.

## M3 — Recursive frontier

**Status: partial, integrated with the existing bounded two-hop scanner.**

Implemented: admission policy, visited/depth rejection, shared pivot budget, common-handle corroboration, weak-signal suppression and verified-only second-hop expansion.

Not complete: a general persisted multi-signal frontier, stored rejected-pivot diagnostics and configurable per-signal recursion budgets.

## Evidence and provenance

The existing universal `Evidence` model remains the canonical evidence layer. It can retain provider ID, URL, evidence kind, retrieval/observation timestamps, verification state, reliability, content SHA-256, parser version, historical/current state and confidence.

**Invariant:** a high numeric confidence value does not automatically become `Verified`.

Remaining gap: older producers do not yet populate every provenance field consistently.

## M4 — Identity Graph v2

**Status: partial, production-integrated.**

Implemented: full semantic `GraphEntityKind`, stable legacy type compatibility, typed relationship taxonomy, node state, edge evidence IDs, contradiction IDs, historical flags, first/last observations, graph schema version and relationship/history/conflict queries.

Not complete: graph-only truth across every subsystem, complete evidence IDs on legacy edges and full archive/image/breach population at contract depth.

## M5 — Entity Resolver v2

**Status: partial, integrated but uncalibrated.**

Implemented: explainable contributions, independent URL/verification/cross-link/email/name/organization/location signals, contradiction handling, conservative confidence bands and an explicit regression proving a shared username alone cannot confirm identity.

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
- stable `imgcandidate:` identifiers;
- candidate provenance containing source, source page, acquisition query, compared URL, retrieval timestamp, SHA-256, dimensions, aHash/dHash/pHash, comparison score, exact-byte state and comparison state;
- truthful candidate states for download-unavailable, decode-failed, compared-no-match and matched outcomes rather than silently dropping failed candidates;
- deterministic exact-content and perceptual-near-duplicate clustering with stable `imgcluster:` identifiers;
- cluster-to-match/candidate linkage;
- Reverse Media UI with candidate-state totals, provenance cards, source drill-down, technical hash/dimension metadata and duplicate/repost cluster summaries;
- explicit UI wording that whole-image duplicate/repost similarity does not identify a person;
- generic `Dossier/0.1 authorized-public-image-audit` User-Agent instead of browser/device impersonation;
- serialization/backward-compatibility and cluster regression tests.

Not complete:

- persistence of image candidate/cluster objects into `DossierCase`;
- identity-graph edges connecting reused images across verified accounts;
- cross-scan image-cluster history/diff;
- a single generalized `ImageCandidateAcquirer` abstraction covering all future acquisition families.

## M7 — Face validation

**Status: partial.**

Implemented: pinned OpenCV Zoo YuNet/SFace models, exact size/SHA verification, atomic installation, inference-time verification, deterministic preprocessing/alignment, five landmarks, quality/ambiguity rejection, cosine similarity and calibration tooling with identity-disjoint held-out support.

Not complete: adequate legal/consented benchmark, measured ROC/FAR/FRR, demographic/device/pose/age evaluation and representative physical-device latency/thermal/battery validation. Reference thresholds remain engineering values until measured.

## M8 — Historical identity

**Status: partial.**

Implemented: bounded exact-URL Wayback lookup, snapshot retrieval, archive-only verification, historical confidence ceiling, current/historical labeling and timeline construction from real timestamps only.

Not complete: broader archive discovery, extraction of historical username/avatar/bio/organization/location changes, universal archive timestamp propagation and production timeline UI.

## M9 — Breach intelligence

**Status: substantially implemented, externally dependent.**

Implemented: Pwned Passwords range lookup, authenticated privacy-preserving email range lookup where supported, no full-address silent fallback, authoritative/public-web separation, explicit unavailable/configuration states and breach/provider/date/retrieval/data-class provenance.

## M10 — Evidence-grounded AI

**Status: partial, production-integrated.**

Implemented: deterministic structured evidence snapshot, structured claim/result contract, evidence-ID validation, uncited factual-claim rejection, contradiction downgrade, output bounds, raw-prose rejection and deterministic local fallback.

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
- Reverse Media candidate provenance and duplicate/repost cluster inspection.

Not complete: production timeline, case-integrated/cross-account image cluster review, corrections directly on every live Evidence card, mature tablet/landscape adaptation, localization/RTL and complete accessibility validation.

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
- user-visible recheck section that explicitly says disappearance from one newer scan is not proof that search indexes, caches, archives or every live copy are gone;
- emulator acceptance tests proving scan history remains bound to the original case when later corrections/remediation edits occur.

Not complete: broad provider-specific removal/deletion/correction links, automatic association of every later scan with earlier remediation records and explicit live/search-index/archive three-way removal verification.

## Reports and export

Full export provides PDF + JSON evidence packages with per-section SHA-256 hashes and a manifest hash. Hashes are integrity metadata, not independent attestation.

`ShareSafe` redaction is exposed from saved Cases and runs **before files are written**. It removes/generalizes subject names, finding values, source URLs, snippets, profile details, visual URLs, breach identifiers, graph details and generated analysis that may reproduce identifying evidence. The package records redaction mode and the UI warns that redaction reduces disclosure but cannot guarantee anonymity.

## Security and privacy

Implemented controls include Keystore-backed AES-256-GCM cases, versioned/atomic encrypted storage, no new plaintext fallback, explicit deletion, restricted evidence WebView, system Photo Picker, local visual/face processing, opt-in remote AI, pre-write share-safe redaction, PII-safe scan-history binding and non-impersonating image-candidate HTTP identity.

No newly introduced challenge bypass, credential acquisition, private-source access, hidden tracking or traffic-evasion behavior is permitted.

## Validation record

Validated implementation commit:

```text
ead2e9806914ee0bd525a2c9d0a548c17348f5fa
```

Passing gates on that exact commit:

```text
Provider registry audit       PASS
Face calibration runtime      PASS
JVM unit tests                PASS
Debug APK assembly            PASS
API 35 Compose tests (9/9)    PASS
```

The Compose suite covers consent/navigation/identity validation, scan-mode selection, encrypted scan-history/correction/remediation persistence and non-overclaiming remediation recheck UX.

This remains emulator validation only. No physical device is recorded as release-validated.

## Current production blockers

1. Grow Discovery Fabric from 78 definitions toward the contract's 1,000+ useful reviewed providers with automated maintenance/import and live provider-contract validation.
2. Make v2 scheduling universal: per-provider rate policy, provider-level lifecycle events and persisted health.
3. Implement true coordinator-owned pause/resume/crash recovery and a persisted general recursive frontier.
4. Calibrate entity resolution with a representative benchmark and publish precision/recall/FPR/FNR/calibration results here.
5. Finish universal evidence/provenance population and graph-as-sole-truth migration.
6. Persist image candidate/cluster provenance into encrypted cases and the identity graph; correlate reused images across verified accounts and cross-scans.
7. Run the face-correlation benchmark and publish measured ROC/FAR/FRR thresholds, then validate on representative physical devices.
8. Complete historical extraction and production timeline UI.
9. Build a production AI evaluation corpus and corrected-graph-native input path.
10. Complete live Evidence correction UX and broad provider-specific remediation/recheck workflows.
11. Perform physical Samsung/Pixel/lower-memory QA, process-death/background recovery, font/display-scale, TalkBack/switch/keyboard, battery/network/thermal and large-case performance testing.
12. Complete release/security hardening and packaging.

## Non-negotiable limitations

- Dossier cannot guarantee discovery of private, authenticated, never-indexed or never-archived content.
- Provider availability, indexing and API access are externally controlled.
- Search results and visual/face similarity remain evidence leads until corroborated.
- Whole-image duplicate clusters describe copied/reposted image content, not identity across unrelated photos.
- Historical snapshots may be missing, stale or incomplete.
- Face model integrity does not establish real-world recognition accuracy.
- HIBP account coverage depends on compatible user-supplied access and provider availability.
- `NotObservedInLatestScan` is not equivalent to verified deletion.
- Emulator CI cannot substitute for representative physical-device testing.

## Documentation policy

Do not create separate roadmap, audit, status, progress or handoff Markdown files. `AGENTS.md` is the target contract; update this document after meaningful validated milestones and remove superseded claims rather than accumulating contradictory records.
