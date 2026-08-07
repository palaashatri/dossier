# AGENTS.md — Dossier Product Contract

## 0. Purpose

This document is the authoritative implementation contract for Dossier.

Dossier is a local-first personal digital-footprint, privacy-exposure, account-discovery, identity-correlation, breach-awareness, historical-evidence, and remediation application for self-audits and explicitly authorized subjects.

The final product must behave as one evidence-oriented privacy system: accept authorized identity seeds, discover permitted public sources, verify candidates, extract structured identity signals, perform bounded recursive discovery, build an explainable graph, correlate accounts and public images using multiple independent signals, retrieve historical and authoritative breach metadata where legitimate integrations allow it, explain risk, support evidence-grounded AI analysis, and help the user reduce exposure.

Dossier is not a platform for covert monitoring, unauthorized access, private-data acquisition, or evading access controls.

## 1. Repository rules

The repository root contains only three canonical prose documents:

- `AGENTS.md` — immutable product and engineering contract.
- `README.md` — user-facing project description.
- `TRUTH.md` — current factual implementation and readiness record.

Do not add root-level roadmap, audit, milestone, handoff, or status documents. Update `TRUTH.md` instead.

Never weaken this contract because a milestone is difficult. Never report a capability as complete merely because an interface exists, a screen renders, mocks pass, or code compiles. End-to-end acceptance criteria and validation determine completion.

Prefer deterministic systems before AI, local computation before remote computation, explicit evidence before inference, explainability before opaque scoring, bounded work before uncontrolled recursion, declarative providers before site-specific code, platform APIs before brittle workarounds, secure persistence before plaintext, and truthful unsupported states before simulated success.

## 2. Product workflow

The target workflow is:

```text
Authorized identity seeds
  → scan configuration
  → discovery fabric
  → candidate verification
  → evidence extraction
  → bounded pivot frontier
  → entity resolution
  → identity graph
  → image correlation
  → historical analysis
  → breach intelligence
  → exposure analysis
  → evidence-grounded AI analysis
  → investigation dashboard
  → remediation
  → report/export
```

A disconnected collection of scanners is not the finished product.

## 3. Safety and authorization boundary

Production behavior is limited to user-provided data, publicly accessible information, legitimate public APIs, explicitly connected services, and user-authorized local data.

When a source rejects automated access or requires authorization Dossier does not possess, return a truthful structured state such as `Unavailable`, `RateLimited`, `AuthenticationRequired`, `UnsupportedAutomation`, or `ProviderChanged`. Do not bypass the restriction.

Do not collect private communications, secrets, credentials, or non-public device data. Do not implement hidden tracking, compromise, access-control evasion, or traffic-evasion mechanisms. Do not distribute raw leaked credential material.

## 4. Evidence principles

Every substantive claim must connect to inspectable evidence. Confidence is not proof.

Inferred relationships distinguish at least:

```text
Confirmed
High
Medium
Low
Unresolved
Conflicting
```

Evidence provenance should retain, as applicable:

```text
source/provider
canonical URL
retrieval timestamp
observed timestamp
evidence type
verification state
content hash
historical/current state
parser version
source reliability
```

Historical evidence must remain visibly historical. Search results are candidates until directly verified where possible. Visual similarity is supporting evidence, not identity proof. Authoritative breach metadata remains separate from ordinary public-web exposure.

## 5. Core domain model

Evolve the current model into a typed graph supporting at least:

```text
Subject, Account, Username, DisplayName, Email, Phone, Domain, URL,
Image, Organization, Location, Occupation, Document, ArchiveSnapshot,
Breach, Website, EvidenceArtifact
```

Relationships include at least:

```text
HAS_USERNAME, HAS_EMAIL, HAS_PHONE, USES_ACCOUNT, USES_AVATAR,
LINKS_TO, MENTIONS, OWNS_DOMAIN, AFFILIATED_WITH, LOCATED_IN,
APPEARED_IN_BREACH, ARCHIVED_AS, SAME_IMAGE_AS, SIMILAR_IMAGE_TO,
VISUALLY_SIMILAR_TO, REDIRECTS_TO, CLAIMS_IDENTITY,
CROSS_LINKS_ACCOUNT, DERIVED_FROM
```

Every relationship must be independently evidential. Storage must not be permanently coupled to UI models.

## 6. Discovery Fabric v2

This is the highest-priority major capability.

Build a declarative provider registry capable of several thousand definitions without one Kotlin implementation per site.

A provider definition should capture:

```text
id
display name
category
profile URL template where applicable
query capabilities
existence rules
extraction rules
priority
regions
tags
enabled state
source reliability
request policy
```

Provider categories include Developer, Social, Forum, Gaming, Creative, Publishing, Professional, Media, Commerce, Education, CodeHosting, PackageRegistry, PersonalWebsite, PublicDirectory, Archive, BreachMetadata, and SearchEngine.

Provider definitions must be validated for duplicate IDs, malformed templates, unsupported schemes, invalid priorities, contradictory HTTP rules, soft-error handling, and policy state. A definition is not considered healthy just because it returns HTTP 200.

Target 1,000+ useful legitimate definitions over time. Never fabricate provider count to satisfy the target. `TRUTH.md` records the actual validated count.

## 7. Scan modes and scheduling

Support four explicit budgets:

- Quick — highest-value subset for ordinary mobile use.
- Standard — balanced provider coverage.
- Deep — larger provider and historical coverage.
- Exhaustive — all enabled providers, explicit opt-in, resumable and cancellable.

Progress always derives from scheduled operations; never hardcode fake totals.

Each provider has bounded concurrency, minimum request spacing, timeout, retry budget, and cooldown. Global network concurrency is bounded. Provider failures remain isolated.

## 8. Scan coordinator and event bus

Introduce a central scan coordinator responsible for seed ingestion, deduplication, plan construction, scheduling, cancellation, persistence, resume, pivot budgets, and event streaming.

Every meaningful operation produces a typed event. At minimum the event model supports scan start/completion, provider queue/start/completion/unavailable, candidate discovery, evidence verification, identity-signal discovery, pivot admission/rejection, relationship creation, image discovery/match, archive discovery, breach discovery, and analysis updates.

The UI updates incrementally from real events.

## 9. Candidate verification and extraction

Discovery and verification are separate. A URL existing does not prove account ownership.

Verification must detect ordinary not-found responses, soft errors, redirects, login walls, policy challenges, malformed responses, and pages that exist but cannot be attributed to the subject.

Provider extraction should capture structured public signals such as username, display name, bio, public contact details, websites, account links, avatar, banner, location claim, organization claim, occupation claim, join date, external IDs, and canonical URL when legitimately present.

## 10. Recursive discovery frontier

Replace ad-hoc pivoting with a bounded frontier containing visited signals/providers, admitted and rejected pivots, depth, and budgets.

Default maximum depth starts conservatively at two. Strong explicit cross-links and supplied identifiers may expand automatically. Weak or common signals do not recursively expand without corroboration. The purpose is to improve recall without false-positive cascades.

## 11. Entity resolution

Entity resolution combines independent positive and negative signals. Never implement a `same username = same person` shortcut.

Preserve contribution explanations and supporting evidence IDs for every inferred relationship. Contradictions such as incompatible names, websites, timelines, or strongly conflicting visual evidence must reduce or block a merge.

Weights are engineering parameters until calibrated against benchmark data and must not be described as scientific probabilities.

## 12. Identity Graph v2

The graph becomes the central internal representation:

```text
Discovery → Evidence → Graph → Correlation → Analysis → UI
```

Support queries for linked accounts, evidence behind a relationship, shared identifiers, reused images, earliest known evidence, historical changes, organizations, breach links, unresolved candidate branches, and conflicting evidence.

Do not create parallel sources of truth.

## 13. Images and local face correlation

Acquire candidate images only from public/authorized sources and preserve provenance. Use cheap deterministic deduplication before face comparison:

```text
download → SHA-256 → decode → exact cluster → perceptual hashes
→ normalized crops → face detection where useful → embedding where useful
```

Maintain the current local-first YuNet/SFace approach with pinned redistributable models, integrity checks, deterministic preprocessing, documented color conversion, alignment, quality rejection, embedding normalization, and reproducible cosine comparison.

Face similarity remains supporting evidence. Production thresholds require a consented or legally distributable identity-disjoint benchmark with FAR/FRR/ROC-oriented reporting and a versioned calibration artifact.

## 14. Historical identity and timeline

Archive evidence is first-class and remains distinct from current claims. For verified current URLs, permitted archive lookups may produce snapshots and timestamped evidence. Extract historical names, usernames, avatars, public links, organization/location claims, and other supported signals without silently merging old and current state.

Construct an inspectable identity timeline from timestamped evidence.

## 15. Breach intelligence

Use legitimate breach-intelligence providers and privacy-preserving query modes where available. Never display or distribute secret credential material.

Breach records preserve breach name, relevant dates, data classes, verification state, provider, retrieval time, and affected supplied identifier. Breach events may appear on the timeline but remain semantically separate from public-profile observations.

## 16. Exposure analysis

Prioritize exposure by inspectable categories such as public accounts, contact exposure, history, image reuse, location, employment, breach exposure, identifier reuse, data-broker exposure, and high-value account exposure.

If Dossier exposes an aggregate score, its contributing factors must be inspectable. Weights remain documented engineering parameters until calibrated and user-tested.

## 17. AI analyst

AI consumes a deterministic structured snapshot derived from evidence and graph state. AI does not create evidence.

Structured AI conclusions must cite existing evidence IDs, distinguish supporting and contradicting evidence, and return an uncertainty-aware summary. Reject or downgrade output that cites nonexistent evidence or asserts unsupported facts.

Local models are preferred where practical. Remote providers remain opt-in and the UI discloses what leaves the device.

## 18. Investigation UX

Keep the coherent four-area investigation structure:

- Overview — privacy posture and scan summary.
- Evidence — findings and provenance.
- Connections — identity graph and explanations.
- Actions — remediation.

During scans, display real scheduled/completed provider counts and stream useful discoveries as they occur.

Evidence cards should expose source, URL, timestamp, current/historical state, verification state, confidence, relationship to subject, and safe normalized/raw values. Users must eventually be able to confirm, reject, ignore, or annotate evidence, and corrections must influence downstream graph/risk/AI behavior without silently deleting raw evidence.

Connections must support accessible navigation and a non-visual alternative. Status must never depend on color alone.

## 19. Remediation and differential rescans

Remediation supports provider-specific privacy/deletion/correction resources where legitimate, action-state tracking, and later verification. Never report that data was removed unless a later check supports that claim.

Persist remediation states such as NotStarted, InProgress, Submitted, AwaitingResponse, Completed, Rejected, and NeedsManualAction.

Scan history must retain enough state to compare new, changed, unchanged, unavailable/removed, and historical-only evidence across rescans.

## 20. Cases, reports, and local security

A versioned encrypted case eventually contains authorized scope, seeds, scan history, evidence, graph entities/relationships, image clusters, breach metadata, analysis, user corrections, remediation state, and export metadata.

Sensitive case data must use platform-backed secure storage where available. Do not log secrets. Do not commit API keys. Credentials use secure storage. Case deletion and cache cleanup are explicit.

Exports support machine-readable evidence JSON and human-readable PDF/HTML where appropriate. Reports include scope, scan time, inputs, findings, confidence, provenance, timeline, risk categories, remediation checklist, limitations, and redaction controls.

## 21. Offline and privacy controls

Users eventually control local-only mode, remote-AI permission, scan depth, provider categories, historical lookup, image analysis, face correlation, breach query, case persistence, and export redaction. Defaults favor privacy.

Offline operation should retain useful case browsing, graph inspection, local image/face comparison, deterministic analysis, local AI where installed, report generation, and cached evidence. Network-dependent providers show truthful unavailable states.

## 22. Provider reliability and caching

Track provider success, timeout, soft-error, rate-limit, and parser-failure rates, median latency, and last validation date. Development diagnostics should expose stale/broken providers.

Cache entries retain provider, request key, timestamp, expiry, content hash, and validation state. Stale data never masquerades as a fresh fetch.

Use a structured error taxonomy including NetworkUnavailable, Timeout, RateLimited, AuthenticationRequired, UnsupportedAutomation, ProviderChanged, ParseFailure, InvalidCandidate, PolicyRestriction, RemoteServiceUnavailable, ModelUnavailable, StorageFailure, and Cancelled.

## 23. Testing contract

Unit tests cover normalization, provider parsing/validation, graph operations, confidence contributions, pivot admission, hashing, image preprocessing, evidence serialization, risk calculation, and AI-result validation.

Provider contract tests include present, absent, soft-error, redirect, policy/auth, and malformed fixtures. Deterministic fixtures remain separate from live network checks.

Recursive discovery tests use synthetic identity networks and verify valid multi-hop discovery, depth limits, loop prevention, weak-branch rejection, and contradiction handling.

Entity-resolution benchmarks measure precision, recall, false positives, false negatives, and calibration on synthetic/consented data. False positives are especially costly.

Image tests cover exact copies, recompression, resize, crop, small edits, different photos of the same consented subject, different subjects, no-face, multi-face, low-quality, and pose variation.

Security tests cover case encryption, key behavior, credential handling, deletion, redaction, logs, and accidental remote transmission.

Compose instrumentation covers critical flows including seed entry, scan configuration, cancellation, resume, finding inspection, graph drill-down, image review, breach review, remediation, export, and case deletion.

## 24. Real-device, performance, accessibility, and UX gates

Production readiness requires real-device validation on at least a recent Samsung flagship, a Pixel-class device, and a lower-memory Android device. Measure startup, scan responsiveness, memory, battery/network behavior, rotation/resizing, background restoration, process-death recovery, and large-case performance.

Architecture must remain capable of thousands of provider definitions, bounded concurrent work, thousands of graph nodes, tens of thousands of relationships, incremental UI updates, resumable scans, and bounded memory. Profile before optimizing.

Production UI requires screen-reader semantics, scalable typography, sufficient contrast, reduced-motion handling, non-color-only status, accessible graph alternatives, and suitable touch targets.

Dossier should feel like a modern privacy application rather than a terminal-themed demo. Animation communicates state, remains interruptible, honors reduced motion, and never blocks work.

## 25. No fake capability

Production code must not inject fabricated findings, counters, confidence values, AI responses, graph nodes, breach data, archive data, or successful placeholders. Mocks belong only in explicit tests/fixtures.

## 26. Observability and dependency policy

Development diagnostics may expose provider requests/latency, parse failures, pivot decisions, graph mutations, AI validation failures, image stages, memory, case size, and network failures without unnecessarily logging sensitive values.

Dependencies must be justified, maintained, license-compatible, security-reviewed, and pinned appropriately. Avoid large frameworks for trivial utilities.

Provider-maintenance tooling must validate definitions, duplicate IDs, templates, schema, statistics, staleness, and sampled health. Import tooling may transform legitimate public metadata into Dossier's own reviewed schema, but production must not depend on external CLIs.

## 27. README and TRUTH requirements

`README.md` describes only implemented capabilities plus honest limitations, privacy model, build instructions, supported provider categories, breach requirements, AI options, and security considerations.

`TRUTH.md` must always include:

```text
overall strict product score
subsystem scores
implemented / partial / not implemented
validated / unvalidated
known defects
production blockers
last validated commit
last validated devices
actual validated provider count
```

## 28. Strict 100-point rubric

```text
Discovery breadth and reliability        15
Recursive orchestration                  10
Evidence/provenance                      10
Entity resolution                        10
Identity graph                            8
Image acquisition/correlation             8
Face-correlation validation               6
Historical evidence                       6
Breach intelligence                       5
AI analyst                                5
UX/UI                                     8
Security/privacy                          4
Testing/device validation                 5
                                         ---
                                         100
```

Never award full points without real validation.

## 29. Milestones

### M0 — Baseline audit

Build, run tests, inspect architecture/screens/stubs, inventory providers, graph, image/face, archive/breach, and refresh `TRUTH.md`.

### M1 — Discovery Fabric v2

Deliver declarative provider schema/registry, categories, validation, health, scan presets, bounded request policy, maintenance tooling, and the closest honestly validated provider count toward the 1,000+ target. Real providers must be queried, soft errors handled, fake counts impossible, and failures visible.

### M2 — Scan Coordinator + live events

Deliver central coordination, resumable/cancellable scans, typed events, real-time progress, and duplicate suppression.

### M3 — Recursive frontier

Deliver bounded pivot queue, visited state, confidence admission, depth/budget enforcement, and loop prevention.

### M4 — Identity Graph v2

Deliver typed nodes/relationships, edge evidence, historical state, graph queries, migrations, and one graph source of truth.

### M5 — Entity Resolver v2

Deliver multi-signal resolution, contradiction handling, contribution explanations, benchmark, and calibration tooling.

### M6 — Image acquisition + correlation

Deliver public candidate acquisition, deduplication/clustering, provenance, and face-pipeline integration.

### M7 — Face validation

Deliver benchmarked thresholds and measured FAR/FRR/ROC behavior with versioned calibration.

### M8 — Historical identity

Deliver archive snapshot discovery, historical extraction, timeline, and historical graph relationships.

### M9 — Breach intelligence

Deliver authoritative integration, privacy-preserving lookup where supported, timeline, and quota/error handling.

### M10 — Evidence-grounded AI

Deliver graph snapshot, structured output, evidence-ID validation, contradiction-aware summaries, and local/remote privacy controls.

### M11 — Premium investigation UX

Deliver polished overview, live scan, evidence explorer, graph interactions, timeline, image clusters, risk breakdown, remediation dashboard, and accessibility validation.

### M12 — Remediation workflow

Deliver provider-specific actions, request assistance, status tracking, rescan verification, and before/after comparison.

### M13 — Production hardening

Deliver encryption/migration validation, crash recovery, offline behavior, large-case performance, real-device QA, accessibility, battery/network profiling, and release packaging.

## 30. Completion and agent rules

A milestone is complete only when production implementation exists, relevant tests pass, integration/UI wiring works, error paths are truthful, canonical documentation matches reality, and `TRUTH.md` is updated.

Agents must inspect before rewriting, preserve working behavior, add tests, execute them, fix regressions, avoid speculative claims/dependencies/duplicate architecture, keep changes reviewable, and update `TRUTH.md`.

After every meaningful tranche ask:

```text
Does it compile?
Do tests pass?
Does it work end-to-end?
Is the UI wired?
Are errors truthful?
Are findings evidence-backed?
Could this create false identity links?
Could sensitive data leave unexpectedly?
Did performance regress?
Does TRUTH.md reflect reality?
```

Production code favors explicit types, structured concurrency, bounded resources, deterministic parsing, immutable evidence records where practical, structured failures, and maintainable module boundaries. Adapt the existing repository instead of reorganizing it for style alone.

## 31. Definition of 100/100

Dossier earns 100/100 only when an authorized user can create an encrypted case, supply identity seeds, select scan depth, scan hundreds or thousands of legitimate public sources, watch real progress, verify relevant accounts, safely follow corroborated pivots, inspect an explainable graph, review image and face-supporting evidence, inspect history and authoritative breach metadata, receive evidence-grounded analysis, understand risk, perform remediation, rescan to verify change, export a useful report, and do all of this with production-level privacy, security, performance, accessibility, and stability.

A polished demo, large provider count, AI summary, or identity graph alone is not 100/100. The entire workflow must work together.

## 32. Immediate execution order

```text
1. Audit current repository and refresh TRUTH.md.
2. Preserve working evidence, graph, image, face, breach, AI, case, UI, and tests.
3. Build Discovery Fabric v2.
4. Introduce Scan Coordinator and typed event bus.
5. Replace ad-hoc pivots with bounded recursive frontier logic.
6. Upgrade the identity graph schema.
7. Build calibrated entity resolution.
8. Expand image candidate acquisition.
9. Validate face correlation scientifically.
10. Expand archive/timeline support.
11. Harden breach presentation.
12. Make AI graph-native and evidence-validated.
13. Polish the investigation UX around streaming results.
14. Implement remediation and differential rescans.
15. Perform production hardening and real-device QA.
16. Continue until the strict rubric truthfully reaches 100/100.
```

Do not stop at scaffolding when a vertical implementation can reasonably be completed.

The final product standard is an evidence-backed, explainable, measurable, local-first, privacy-preserving, secure, polished, and actionable self-audit application.