# AGENTS.md — Dossier Product and Engineering Contract

## 0. Purpose

This document is the authoritative implementation contract for Dossier.

Dossier is a local-first personal exposure reconstruction and authorized public-source investigation application. Its primary job is not to enumerate usernames or count providers. Its primary job is to answer:

> Starting with one piece of information the user is authorized to investigate, what exact information can be recovered, from which accessible sources, through what discovery chain, and how quickly?

For self-audit mode the product contract is stricter:

> Starting only from information the user chooses to provide about themselves, reconstruct as completely as practical the exact information an outside observer can recover from accessible sources, preserve the exact exposed values locally, show the evidence and discovery path for every fact, and help the user reduce that exposure.

The implementation must optimize for measured recall, precision, exact-value recovery, provenance, time-to-useful-result, recursive discovery, and remediation. Provider count, test count, UI completeness, graph size, or the existence of an integration stub are not substitutes for those outcomes.

Dossier is not being rewritten from scratch. Preserve and reuse proven foundations unless an audit demonstrates they are incorrect, duplicated, unsafe, or obstruct the new mission.

## 1. Canonical repository documents

The repository has three canonical prose documents:

- `AGENTS.md` — product contract, architecture, engineering rules, acceptance criteria, and implementation priorities.
- `README.md` — public-facing description and build/use documentation.
- `TRUTH.md` — factual current implementation state, audit findings, validation evidence, defects, and blockers.

Do not create extra roadmap, audit, handoff, milestone, completion, status, plan, or findings Markdown files. Update these documents instead.

## 2. Product principles

Dossier follows these principles in order:

1. **Measured discovery utility over feature count.**
2. **Exact exposed values over vague exposure categories in local self-audit views.**
3. **Evidence and provenance over unsupported inference.**
4. **Recursive discovery over one-shot enumeration.**
5. **Useful results early while deeper work continues.**
6. **Local processing and local sensitive storage by default.**
7. **Truthful source limitations over simulated capability.**
8. **Preserve good existing infrastructure instead of gratuitous rewrites.**
9. **Delete dead, duplicated, generated, misleading, or purely theatrical code and tests.**
10. **Do not weaken identity attribution standards just to improve apparent recall.**

## 3. Primary user experience

### 3.1 Launch screen

Application launch must present one dominant universal search control, not a dashboard or multi-step identity wizard.

The first screen contains:

- one text field;
- a camera action;
- an upload/gallery action;
- minimal supporting copy;
- recent local cases only if they do not compete visually with the primary search action.

The universal text input accepts at least:

- person name;
- username or handle;
- phone number;
- email address;
- public profile URL or other URL;
- other explicitly supported seed types added later.

The app detects the likely seed type locally. Detection must be inspectable and correctable by the user when ambiguous. The app must not require the user to manually populate a large identity form before useful discovery begins.

Example conceptual flow using synthetic data only:

```text
Search name, username, phone, email or URL…     [camera] [upload]
```

Typing `Jane Example`, `@sample_user`, `jane@example.test`, a test phone value, or an authorized URL must enter the same discovery system with the appropriate initial seed type.

### 3.2 Progressive results

After search starts, Dossier streams evidence as soon as it is verified or meaningfully classified. The user must not wait for the entire scan before seeing useful findings.

The results surface converges on three first-class representations:

- **Exposure Ledger** — exact facts and where they were observed;
- **Exposure Graph** — relationships and discovery paths between facts, sources, accounts, documents, images, and historical observations;
- **Attacker View** — what was derivable from the original seed alone and in what order.

## 4. Universal discovery architecture

All supported initial seed types enter one discovery system:

```text
Initial seed
  → normalization and seed classification
  → query expansion
  → rolling multi-source scheduler
  → search / profiles / documents / archives / exposure indexes / image providers
  → fetch and content extraction
  → verification and provenance
  → Exposure Ledger + Exposure Graph
  → newly verified identifiers
  → priority frontier
  ↺ until exhausted, cancelled, or budget reached
```

Do not build separate disconnected scanners whose findings never become new search pivots.

## 5. Seed types and frontier

The frontier must support typed seeds. Initial and discovered seed kinds should include at least:

```text
Name
Username
Email
Phone
URL
Domain
Photo
ImageURL
Organization
Location
PostalCode
Document
Profile
Alias
```

Additional types may be introduced when justified by observed benchmark misses.

Each frontier item stores at least:

```text
normalized value
exact observed value when applicable
seed type
origin evidence IDs
parent discovery path
depth
priority
confidence / verification state
first seen timestamp
last attempted timestamp
provider/source eligibility
visited state
```

Every verified useful identifier may become a pivot subject to confidence, recursion, provider, and budget rules.

Weak/common signals must not create uncontrolled identity expansion. A common name, broad location, generic occupation, or visual resemblance alone must not recursively merge unrelated people.

## 6. Discovery Engine v3

Discovery Engine v3 is the highest-priority product subsystem.

### 6.1 Rolling scheduler

Replace fixed batch barriers and serial evidence-family execution with a rolling bounded scheduler.

Requirements:

- global network concurrency is bounded;
- per-domain/provider politeness is independent from global concurrency;
- one slow request must not stall an entire fixed batch;
- when one operation finishes, the next eligible operation begins immediately;
- independent evidence families run concurrently when safe;
- cancellation is prompt;
- scan state is persistable and resumable;
- provider cooldowns, challenge states, and rate limits are respected;
- provider failures remain isolated;
- a common shared HTTP client/pool should be reused where practical instead of constructing unnecessary per-call clients;
- priority changes as better evidence is discovered.

Do not solve performance merely by increasing one concurrency constant.

### 6.2 Query expansion

Query generation is adaptive and evidence-driven rather than a short hard-coded list.

For a name seed, query families may include exact-name searches, profile domains, documents, public directories, organizations, locations, archives, and later combinations with newly verified identifiers.

For a discovered email, phone, username, alias, domain, profile URL, or other high-entropy value, generate appropriate exact and normalized variants.

The query system must learn from measured source yield. High-yield queries and sources are scheduled earlier; low-yield or unhealthy routes move later or are skipped within bounded modes.

### 6.3 Search source accounting

Keep these counts separate everywhere:

- catalogued source;
- executable source;
- automated source;
- live-reachable source;
- live-validated source;
- import-only source;
- manual-only source;
- retired/unsupported source.

Never present a large catalogue of manual/import-only tools as autonomous Dossier coverage.

## 7. Exact-value extraction

Exact-value extraction is first-class functionality.

Fetched accessible pages and documents should be parsed for supported exposed data such as:

- names and aliases;
- usernames/handles;
- email addresses;
- phone numbers;
- postal addresses;
- postal/PIN codes;
- public profile URLs;
- websites/domains;
- organization and employment claims;
- location claims;
- messaging/profile identifiers where openly present;
- payment-address-like public identifiers where legitimately visible;
- image and avatar URLs;
- document metadata;
- other structured facts justified by observed user needs.

For every extracted value preserve:

```text
exact source string
normalized value
type
source/provider
source URL or local evidence source
retrieval timestamp
observed/historical timestamp when available
parser version
content/evidence hash
verification state
confidence/source reliability
discovery path
current vs historical state
```

Self-audit local views must not mask an exact value merely because it is sensitive. The user needs to know what is exposed in order to verify and remediate it. Redaction belongs to sharing/export controls, not the private local evidence ledger.

## 8. Exposure Ledger

The Exposure Ledger is a central product object rather than a report-only projection.

Each fact should support at least:

```text
exact value
normalized value
fact type
subject/entity association
one or more evidence IDs
source classification
first observed
last observed
verification state
confidence
historical/current state
discovery path
remediation status
```

Source classifications should include at least:

```text
PUBLIC_WEB
PUBLIC_PROFILE
PUBLIC_DOCUMENT
PUBLIC_RECORD
DATA_BROKER
ARCHIVE
BREACH_INDEX
AUTHORIZED_API
LOCAL_IMPORT
USER_IMPORTED
UNKNOWN_ORIGIN
```

Do not automatically treat a locally imported or breach-derived record as a verified identity fact. It is evidence whose relationship to the audited subject still requires correlation and provenance.

## 9. Attacker View and benchmark mode

Attacker View answers what can be derived from the initial seed without silently using additional user profile information.

The user can choose a starting condition such as:

```text
Name only
Username only
Email only
Phone only
URL only
Photo only
```

Dossier records the derivation timeline:

```text
T+00:00 initial seed
T+00:04 first verified profile
T+00:12 verified username
T+00:18 public email
T+00:31 public document
T+00:44 phone candidate
...
```

The benchmark must measure at least:

- exact facts recovered;
- Recall@known-exposure;
- precision;
- false-positive rate;
- unresolved candidate count;
- time to first useful result;
- time to first verified identity anchor;
- time to email/phone/address when present in ground truth;
- time to 50% recall;
- time to 80% recall;
- total scan duration;
- provider/source failure rate;
- useful findings per request;
- useful pivots per verified finding.

Provider count is not a mission metric.

### 9.1 Private ground truth

Real self-audit ground truth must remain local to the user's device or development machine.

Never commit or upload a real person's:

- name used as a private benchmark identity;
- phone numbers;
- email addresses;
- addresses;
- account identifiers;
- government identifiers;
- financial identifiers;
- photos/face data;
- private bot outputs;
- breach rows;
- benchmark manifests containing real identity values.

Use synthetic fixtures in Git and CI. Private benchmark storage must be gitignored, encrypted where practical, excluded from logs, excluded from screenshots, excluded from telemetry, and never automatically included in AI prompts.

## 10. Photo as a first-class seed

A user-provided photo is a universal discovery seed, not a separate utility screen.

Photo search immediately fans out into independent local and remote-capable pipelines:

```text
Photo
  ├─ metadata / EXIF / XMP / IPTC
  ├─ OCR
  ├─ face detection and local face correlation
  ├─ object / landmark / scene cues
  ├─ image hashing and duplicate clustering
  ├─ reverse-image provider search
  └─ location reconstruction
        ↓
   evidence fusion
        ↓
   identity/location candidates
        ↓
   normal recursive Dossier frontier
```

### 10.1 Metadata

Analyze the original image locally before any upload.

Extract where available:

- GPS latitude/longitude/altitude;
- capture timestamp/timezone;
- camera/device model;
- orientation;
- software/editor fields;
- image dimensions;
- embedded thumbnail metadata;
- XMP/IPTC location fields;
- GPS direction/bearing;
- other relevant local metadata.

EXIF coordinates are evidence that coordinates were embedded in the file. They are not automatically proof that the visible scene was captured there if other evidence contradicts them.

### 10.2 OCR

OCR all useful visible text locally where possible. Text such as place names, business names, roads, transit labels, phone numbers, domains, usernames, event names, signs, and postal codes may create new discovery pivots.

OCR observations must retain bounding/provenance information sufficient for user inspection where practical.

### 10.3 Face analysis

Reuse the existing local face pipeline where sound.

The desired flow is:

```text
input photo
  → face detection
  → quality gate
  → aligned crop
  → local embedding
  → public candidate images discovered by other providers
  → local candidate comparison
  → evidence-backed candidate identity
  → newly verified name/username/profile seeds
```

Face similarity is supporting evidence, never identity proof by itself.

Do not automatically send face crops or embeddings to remote AI providers.

### 10.4 Reverse image search

Support multiple reverse-image providers through a provider adapter contract. Prefer documented APIs where available.

Where a useful service has no stable API, a user-visible embedded browser or browser-backed adapter may be used when technically and contractually appropriate. Such adapters must fail truthfully with states such as `ProviderChanged`, `AuthenticationRequired`, `Challenge`, or `UnsupportedAutomation` rather than using brittle or evasive behavior.

Playwright is appropriate for browser-adapter CI, regression testing, and external tooling. Do not make Playwright-over-ADB a required Android runtime architecture.

Candidate providers may include services such as Google Lens, Yandex Images, TinEye, and later reviewed services. Provider availability and integration mode must be recorded truthfully in `TRUTH.md`.

Reverse-image results should yield at least:

```text
candidate image URL
source page URL
provider
page title/snippet when available
exact-copy / near-duplicate / visual-candidate state
retrieval timestamp
provider evidence IDs
```

Source pages discovered through reverse-image search must feed back into normal Dossier fetching, extraction, and recursive search.

### 10.5 Metadata-stripped remote copies

By default, before sending a photo to a remote reverse-image service:

```text
original image
  → local metadata extraction
  → decode pixels locally
  → remove EXIF/XMP/IPTC and unrelated metadata
  → create temporary derivative
  → upload derivative only
```

The UI must disclose that a derivative image will leave the device and identify the destination service.

## 11. Photo location reconstruction

Photo geolocation/location reconstruction is a first-class evidence product.

Candidate location evidence may come from:

- embedded GPS metadata;
- explicit source-page geotags;
- exact/near-identical reverse-image source pages;
- OCR of unique place names or addresses;
- landmark matches;
- multiple independent page/location references;
- public map/place resolution of already discovered location strings;
- visual environmental cues;
- AI/geolocation model suggestions.

Weight these evidence classes differently. Visual guessing must never be presented at the same confidence tier as exact GPS or multiple independent corroborating sources.

Location results use explicit classes such as:

```text
EXACT_METADATA
CORROBORATED_LOCATION
LIKELY_LOCATION
VISUAL_GUESS
CONFLICTING
```

Every location candidate must show why it exists.

Where multiple candidate locations exist, display ranked candidates and their evidence rather than forcing one coordinate.

## 12. Documents, archives, directories, and historical identity

Public documents are first-class sources. Discovery must handle relevant accessible PDFs, text documents, public directories, event lists, resumes, organization pages, indexed spreadsheets where safely parseable, and similar evidence.

Extract text and metadata locally after retrieval and feed verified identifiers into the frontier.

Archives are first-class historical evidence. Historical names, handles, links, contact information, avatars, organizations, and locations remain visibly historical and must not silently overwrite current state.

## 13. Breach and exposure intelligence

Breach intelligence supports the self-audit mission but does not replace public discovery.

Use legitimate exposure/breach providers and privacy-preserving query modes where available. A provider may confirm that a class of data was exposed without returning the original stolen value; Dossier must represent that distinction explicitly.

Support local ingestion of user-authorized exposure evidence and datasets where practical, including structured CSV/JSON/JSONL and other reviewed formats. Larger or more complex formats may be added when justified.

Imported local exposure data remains local by default and must never be automatically uploaded.

Dossier may use an exact identifier learned from authorized local evidence as a pivot into public/authorized sources while preserving provenance.

Do not build acquisition mechanisms for stolen/private databases or credentials.

## 14. Evidence and identity resolution

Preserve the existing evidence-oriented philosophy.

Every substantive claim must connect to inspectable evidence IDs. Confidence is not proof.

Entity resolution combines independent positive and negative signals and must preserve explanations.

A same/similar username alone cannot confirm identity. A face similarity score alone cannot confirm identity. A search snippet alone cannot confirm identity. A common name alone cannot confirm identity.

Contradictions remain first-class evidence and may block or downgrade a merge.

## 15. Graph architecture

Reuse the existing typed evidence/graph foundations where possible, but make the Exposure Ledger and canonical evidence relationships the source material from which graph projections derive.

The graph should support at least:

```text
Subject
Account
Username
DisplayName
Email
Phone
Address
PostalCode
Domain
URL
Image
Photo
Document
Organization
Location
ArchiveSnapshot
Breach
Website
EvidenceArtifact
```

Useful relationships include:

```text
HAS_USERNAME
HAS_EMAIL
HAS_PHONE
HAS_ADDRESS
USES_ACCOUNT
USES_AVATAR
LINKS_TO
MENTIONS
AFFILIATED_WITH
LOCATED_IN
OBSERVED_AT
APPEARED_IN_BREACH
ARCHIVED_AS
SAME_IMAGE_AS
SIMILAR_IMAGE_TO
CROSS_LINKS_ACCOUNT
DERIVED_FROM
SOURCE_OF
```

Storage must not depend permanently on one UI graph representation.

## 16. Reuse existing foundations

Do not rewrite working foundations without evidence.

The following existing areas are presumptively reusable and should be adapted rather than discarded:

- encrypted case persistence and bounded records;
- evidence IDs and provenance models;
- canonical relationship assertions;
- graph schema and reconciliation diagnostics;
- WorkManager/background scan ownership and recovery concepts;
- request/plan-bound checkpoint patterns;
- user correction semantics;
- remediation tracking;
- report/export redaction infrastructure;
- archive/Wayback support;
- HIBP/breach provider scaffolding;
- local image hashing and duplicate clustering;
- YuNet/SFace local face-correlation pipeline and quality gates;
- OCR and local image analysis dependencies already used successfully;
- Compose navigation/components that remain useful after the launch-flow simplification;
- provider response taxonomy, bounded reads, cooldowns, and conservative verification logic.

Reuse does not mean preserve all current abstractions. If an existing component forces shallow one-shot behavior, serial execution, duplicate sources of truth, misleading capability claims, unnecessary dependencies, or poor UX, refactor or remove it.

## 17. UI restructuring

The existing multi-screen shell is a base, not a product contract.

Priority UI work:

1. universal launch/search screen;
2. live progressive discovery screen;
3. Exposure Ledger;
4. Attacker View timeline;
5. evidence/source drill-down;
6. Exposure Graph;
7. photo analysis/location candidate panel;
8. remediation actions;
9. saved local cases;
10. settings/advanced scan controls.

Advanced provider selection and technical diagnostics must not block ordinary search startup.

## 18. Provider/browser behavior

Network identity must be generic and product-owned.

Do not hard-code:

- a contributor's device model;
- a contributor's username;
- a contributor repository URL in runtime User-Agent strings;
- fake Chrome/Firefox device identities solely to impersonate browsers or evade provider controls.

Use a generic Dossier product User-Agent where a custom UA is appropriate. Embedded WebView requests may use the platform's normal WebView UA when required for compatibility, but do not override it with a developer-specific hardware fingerprint.

No CAPTCHA bypass, challenge bypass, login/session theft, credential collection, or access-control evasion.

## 19. PII and repository hygiene

Never commit real personal benchmark data.

Use obvious synthetic fixtures such as:

```text
Jane Example
sample_user
jane@example.test
https://profile.example.test/sample_user
```

Use reserved domains such as `example.com`, `example.org`, `example.net`, and `.test` where appropriate.

Before a change is considered complete, scan for:

- real contributor names used as identity fixtures;
- real personal usernames used as fixtures;
- non-reserved email fixtures;
- phone/address/government/financial identifiers;
- developer-specific absolute paths;
- device model fingerprints;
- committed secrets or keys;
- screenshots containing real identity/account/device data;
- local benchmark files;
- IDE/editor state.

Do not commit `.idea/`, `.vscode/`, agent-tool state directories, local caches, build products, temporary screenshots, benchmark secrets, or generated markers that do not contribute to the product.

Do not rewrite Git history unless the repository owner explicitly requests history rewriting. Removing information from the current tree does not remove it from prior commits, forks, caches, release artifacts, or external indexes.

## 20. Code and test pruning

Delete unnecessary material deliberately.

Candidates include:

- duplicate tests that assert the same contract at the same level;
- obsolete tests for removed behavior;
- empty marker files;
- duplicate maintenance scripts where one maintained implementation is sufficient;
- committed IDE/editor/agent state;
- abandoned integrations and unreachable code;
- catalogue entries that exist only as product-theater metadata when a generic import concept is sufficient;
- dependencies with no production or test use;
- stale screenshots no longer referenced by current documentation.

Do **not** delete regression, security, encryption, persistence, provenance, parser, or recovery tests merely to reduce test count. Test count is not a metric; meaningful defect coverage is.

After pruning, all remaining tests must have a clear responsibility.

## 21. Performance contract

The product should prioritize sub-minute useful discovery even when complete deep scans take longer.

Track at minimum:

```text
time to first useful finding
time to first verified identity anchor
time to first high-value exact identifier
frontier queue size
active workers
provider wait/cooldown time
provider p50/p95 latency
useful findings per provider/request
new pivots per finding
cache hit rate
failure/challenge/rate-limit rate
```

The UI should remain responsive while work proceeds. Persist enough frontier/checkpoint state for useful recovery after process death where practical.

## 22. Security and local privacy

Sensitive cases, exact exposed values, private ground truth, face data, imported exposure evidence, and derived identity graph material require local security controls.

Requirements:

- platform-backed encryption/key storage where practical;
- bounded storage;
- explicit case deletion;
- explicit cache cleanup;
- no sensitive values in routine logs;
- no automatic cloud backup assumption for private case material;
- remote AI is opt-in and receives a deliberately constructed/redacted snapshot by default;
- exact sensitive values remain local unless the user intentionally chooses a network operation that requires them;
- reverse-image uploads use metadata-stripped derivatives by default.

## 23. AI

AI is not a discovery source unless a specific evidence-producing provider contract explicitly says otherwise.

AI may:

- summarize evidence;
- explain discovery paths;
- rank remediation priorities;
- help interpret contradictions;
- suggest next reviewed search strategies using already available structured facts.

AI may not create evidence or invent identifiers. Factual AI output must cite existing evidence IDs. Remote AI must not automatically receive the private ground-truth benchmark or raw high-sensitivity identifiers.

AI/UI polish is lower priority than Discovery Engine v3 recall, extraction, scheduling, and benchmarks.

## 24. Testing strategy

### 24.1 Unit/contract tests

Maintain targeted tests for:

- seed classification and normalization;
- query generation;
- frontier admission/deduplication/depth/budgets;
- rolling scheduling semantics;
- provider throttling/cooldowns;
- parser/extractor correctness;
- exact-value normalization while retaining source strings;
- provenance and evidence IDs;
- identity resolution and contradictions;
- document parsing;
- EXIF/OCR/photo metadata;
- image hashing/duplicate clustering;
- face quality and comparison math;
- location evidence fusion;
- breach-provider states;
- encrypted persistence;
- deletion/redaction;
- AI evidence validation.

### 24.2 Synthetic end-to-end discovery corpus

Create a synthetic/consented benchmark corpus where the expected discovery paths are known. It must exercise multi-hop reconstruction rather than only confusion-matrix arithmetic.

Examples should test:

```text
name → profile → username → email → document → phone
username → profile → website → email → archive
photo → OCR/place clue → source page → profile
photo → reverse-image page → location → identity candidate
photo → face candidate → verified account → recursive search
email → exposure provider → public profile/document pivots
```

### 24.3 Private local benchmark

Real user-owned benchmark values stay local and produce aggregate metrics only unless the user opens the local exact evidence view.

CI must not depend on real people or arbitrary live identities.

### 24.4 Device and UX gates

Retain emulator tests, but production readiness eventually requires physical-device measurements, accessibility, large-font behavior, process-death recovery, memory, battery, thermal, and network variability.

## 25. Mission-readiness scoring

The previous rubric that heavily rewarded provider breadth and implementation hardening is retired as the primary product score.

Do not publish a new overall mission-readiness score until a real end-to-end discovery benchmark exists.

When scoring resumes, weight outcomes approximately as follows:

```text
Measured discovery recall and exact-value recovery     25
Precision / false-positive control                     15
Recursive frontier and query expansion                 10
Time-to-useful-result and scheduling                    10
Evidence / provenance / Exposure Ledger                10
Photo / reverse-image / location reconstruction        10
Identity resolution / graph                             7
Breach / document / archive coverage                    5
Local security and privacy                              4
Remediation / UX / accessibility / recovery             4
                                                      ----
                                                       100
```

A subsystem cannot receive full credit from unit tests or architecture alone when its production outcome is unmeasured.

## 26. Implementation priorities

### P0 — Reset and measurement

- rewrite `AGENTS.md` and `TRUTH.md` around the new mission;
- remove hard-coded contributor/device PII and browser fingerprints;
- remove obvious repository junk and duplicate maintenance artifacts;
- fix current CI failures;
- establish the synthetic end-to-end discovery benchmark framework;
- establish local private benchmark storage rules;
- make the universal search/photo launch screen the default entry flow.

### P1 — Discovery Engine v3

- replace fixed `chunked(...).awaitAll()` username batches with rolling scheduling;
- parallelize independent evidence families;
- add provider health/yield/latency prioritization;
- expand general web search beyond the current hard-capped shallow model;
- implement typed recursive frontier persistence;
- stream findings immediately;
- implement exact-value extraction and Exposure Ledger persistence.

### P2 — Documents, exposure sources, and recursion

- first-class document discovery/extraction;
- stronger archive/history pivots;
- public directory/data-broker adapters where appropriate;
- authorized exposure-provider connectors;
- local exposure-evidence ingestion;
- broad recursive query expansion from newly verified identifiers.

### P3 — Photo and location reconstruction

- unify upload/camera as `Photo` seed;
- local metadata extraction;
- OCR pivots;
- reuse existing face pipeline against newly discovered candidate images;
- reverse-image provider adapters;
- metadata-stripped remote derivatives;
- source-page recursive extraction;
- ranked evidence-backed photo location candidates.

### P4 — Graph consolidation and remediation

- make Exposure Ledger/evidence assertions the canonical source for downstream graph projections;
- reduce parallel representations;
- preserve user corrections and contradictions;
- improve remediation resources and differential rescans;
- preserve exact local evidence while supporting share-safe exports.

### P5 — Product hardening

- physical devices;
- accessibility;
- performance/battery/thermal profiling;
- large-case performance;
- release/security review;
- production calibration for entity/face/location confidence where relevant.

## 27. Definition of success

Dossier is successful when a user can launch it, enter one authorized seed or provide one photo, and quickly begin seeing an evidence-backed reconstruction that becomes richer as each verified fact creates new discovery pivots.

The defining acceptance test is not:

> How many providers or tests exist?

It is:

> Starting from only the chosen seed, how much correct exact exposure can Dossier independently recover, how quickly, from which accessible sources, with what evidence, and with how few false positives?

`TRUTH.md` must always answer how close the current implementation is to that standard.