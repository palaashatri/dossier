# Dossier

Dossier is a consent-first Android application for auditing a subject's public digital footprint when the subject owns the information, has consented, or the operator has another legitimate authorization.

It collects public evidence, preserves provenance, separates verification from review-only leads, correlates findings conservatively, and helps track remediation. Dossier does not treat search results, shared usernames, image/face similarity, AI output, or graph connections as proof of identity or account ownership.

## Current status

The current implementation branch is **76/100 under the strict production rubric**.

Validated implementation commit:

```text
94e9311
```

That exact implementation passed the current final-tree build and deterministic validation gates:

```text
Provider registry audit             PASS — 78 definitions (70 profile templates + 8 services)
WhatsMyName catalog integrity       PASS — 716 records / 644 executable HTTPS rules
Debug JVM unit tests                PASS — 631 tests / 109 suites / 0 failures, errors, or skips
uiTest JVM unit tests               PASS — 631 tests / 109 suites / 0 failures, errors, or skips
Android-test Kotlin compilation    PASS — `compileUiTestAndroidTestKotlin`
Debug APK assembly                  PASS — 118,767,289 bytes
Debug APK SHA-256                   019998B218820359FFB7FCA7F88401B79554BCAE1E6E3E8F7E1B2FC1FF127040
uiTest APK assembly                 PASS — 243,319,921 bytes
uiTest APK SHA-256                  846FD1ABC15523CCCB0BEAF90EA6F56BCB56542DD011B501D228E678409DADBC
Android-test APK                    PASS — 1,020,897 bytes
Android-test APK SHA-256             B90DF4FDDB291154044F8E07B89B5B4EB82BF09621D08DA43B59C96A85B4EEE6
Debug lint                          PASS — 0 errors / 69 warnings
uiTest lint                         PASS — 0 errors / 72 warnings
Connected uiTest suite              PASS — 40 tests / 0 failures on API 36 `medium_phone` emulator
```

The complete connected uiTest suite ran on the API 36 `medium_phone` emulator, including the 8 WorkManager pause/resume tests. The uiTest APK was built with a pre-existing uncommitted fixture-only edit that remains outside the implementation commit; its hash is therefore a current-worktree artifact. Physical devices, process-death/reboot, accessibility, and battery/thermal behavior remain unvalidated.

This is not a production-readiness claim. Provider scale/live validation, calibrated identity and face benchmarks, complete coordinator/frontier ownership, verified-account image correlation, and representative physical-device/accessibility/performance validation remain release gates.

See `TRUTH.md` for the authoritative score and blockers. `AGENTS.md` defines the target product contract.

## Visual walkthrough

These captures come from the API 36 Medium Phone emulator. Analysis, report, case and provider-progress states use deterministic **uiTest-only** fixtures; case data is written through the production encrypted stores and provider counters are emitted through the production coordinator callbacks. All identity values and URLs are synthetic and use reserved `.test` domains. The receiver exists only in the uiTest source set and is absent from the debug manifest. The updated scan-budget screen and bottom navigation were also inspected at 1.0x, 1.3x, 1.5x and 2.0x font scale. Static screenshots do not establish TalkBack, whole-product large-font, reduced-motion, adaptive-layout or physical-device acceptance.

| Consent and input validation | Scan configuration |
|---|---|
| <img src="docs/screenshots/01-onboarding.png" width="320" alt="Dossier one-time usage notice with all consent copy clear of the Continue button"> | <img src="docs/screenshots/06-scan-configuration.png" width="320" alt="Scan depth configuration separating direct-profile counts from pinned HTTPS username-rule budgets"> |
| <img src="docs/screenshots/03-identity-invalid-email.png" width="320" alt="Identity form showing an invalid email message and disabled Continue button"> | <img src="docs/screenshots/07-scan-progress.png" width="320" alt="Live scan showing scheduled, completed and unavailable provider counters with separate background and cancel actions"> |

| Background analysis | Evidence-oriented report |
|---|---|
| <img src="docs/screenshots/08-analysis.png" width="320" alt="Background analysis with readable presence states and bounded supporting analysis"> | <img src="docs/screenshots/09-report-overview.png" width="320" alt="Privacy audit report overview with exposure priority and coverage counts"> |

| Explainable connections | Remediation tracking |
|---|---|
| <img src="docs/screenshots/11-report-connections.png" width="320" alt="Connections tab with a visual identity graph and complete email label"> | <img src="docs/screenshots/15-remediation.png" width="320" alt="Saved-case remediation tracking with long actions and status labels on separate rows"> |

## Discovery Fabric

- Typed declarative provider registry with **78 authored provider/service definitions**.
- Separately pinned WhatsMyName username-surface catalog with **716 source records and 644 executable HTTPS rules** after parser and policy filtering. These are not 644 additional registry definitions or live-validated providers.
- Catalog bytes and the bundled CC BY-SA 4.0 license are pinned to upstream commit `e62338e4fc88536a330733d355a9d33a3a1697c6` and checked by exact SHA-256 before release evidence is recorded.
- Developer, social, forum, gaming, creative, publishing, professional, media, commerce, education, code/package, personal-site, archive, breach and search categories.
- Quick, Standard, Deep and Exhaustive scan modes backed by real provider plans rather than fake totals. Username-rule budgets are 50 / 200 / 500 / 644 per normalized handle, with at most three explicit handles; direct-profile counts remain separate.
- Provider query capabilities, source-reliability classes, existence/extraction rules and request policies.
- Direct-profile candidates execute through the declarative provider runtime with live selector-driven extraction, bounded reads, timeout/retry/cooldown policy, provider request spacing, exact/approved-host redirect checks, HTTPS-downgrade rejection and a generic non-impersonating user agent.
- Real queued, started, completed and unavailable callbacks drive scheduled/completed/unavailable counters; planned registry breadth is not presented as completed work.
- Registry validation for duplicates, malformed templates, parser drift and inventory drift.
- Deterministic response states for present, not-found, soft-404, authentication-required, challenged, rate-limited, timed-out, network-unavailable, redirect, unexpected and invalid responses.
- Multiple bounded public-search sources, direct source verification, retries, `Retry-After`, caches and circuit breakers.
- Configured-depth bounded pivots (default two, hard maximum four) with admission rules: weak name/location/occupation/face-only signals do not recursively expand by themselves, and only verified results seed the next depth.
- Exact-URL Internet Archive recovery for some deleted/replaced pages.

The long-term contract calls for 1,000+ useful reviewed providers. `ProviderCatalogV2` currently contains 78 authored definitions (70 profile templates and 8 services); the separate pinned WhatsMyName catalog contains 716 records, of which 644 are executable HTTPS username rules. Neither count is presented as registry-wide live validation.

## Scan orchestration and history

- `ScanCoordinatorRuntime` wraps the mature vertical scan pipeline.
- Structured scan IDs, requests, run states and events.
- Live UI state derives from real scan-stage/profile/face/breach/graph/analysis observations.
- Direct-profile execution emits provider queued/started/completed/unavailable events with one stable scan ID; retries do not inflate unique started counts and stale callbacks are ignored.
- Identity seeds, scan mode, deep-scan choice and per-scan face policy are stored in an Android Keystore AES-GCM resume record; new WorkManager requests receive only its opaque UUID.
- Stable results from the initial direct-profile pass are checkpointed per request, plan and canonical candidate in Android Keystore AES-GCM storage. A restarted worker reuses only exact, unexpired stable outcomes; transient network, timeout, challenge and parser failures are fetched again.
- New encrypted scan requests commit a deterministic SHA-256 fingerprint of the selected declarative provider plan plus a bounded ordered provider-ID summary; older request records remain explicitly resumable without being assigned a retroactive plan.
- The configured-depth pivot path now uses a request-scoped encrypted frontier with queued, visited and completed state, conservative admission, global/per-signal budgets, bounded rejection diagnostics and durable clear tombstones. A cancelled or thrown pivot attempt remains pending for a later exact-scope worker retry.
- Background WorkManager scans now expose exact-owner `Pausing`/`Paused` lifecycle states, retain encrypted checkpoints through terminal cancellation, resume with a fresh work UUID bound to the same request/generation, and surface pause/resume controls in Background analysis.
- Background WorkManager progress and failures are reduced to fixed stage/error codes rather than persisting arbitrary exception or identity text.
- Background enqueue now publishes a generation-bound lifecycle before replacing the old exact WorkManager UUID, promotes only that prepared encrypted request, and reenqueues the same UUID after an authoritative missing-row crash boundary.
- The worker claims the exact owner/request/generation, publishes the encrypted result before durable success, and treats an exact matching result as idempotent success if WorkManager retries after the success-commit boundary.
- Process startup reconciles only `getWorkInfoById` for the persisted owner, retries unavailable lookups without mutation, and never adopts a unique-work-list result by ordering.
- Cancellation persists intent first and reports cancellation only after the exact WorkInfo row is terminal; completion/failure races return through result-aware reconciliation.
- Result replacement uses file and parent-directory sync plus atomic replacement, while cleanup is exact owner/request/generation scoped.
- Terminal scan lifecycle stores actual start/end time, mode, plan size, result counts and cancellation state.
- A SHA-256 fingerprint of normalized seed values binds a completed scan to the matching initial explicit encrypted case save without maintaining a duplicate plaintext identity cache.
- Later case edits cannot silently attach a newer scan to an older case.

Recovery now covers stable initial direct-profile outcomes, the configured-depth pivot frontier and an exact-owner background pause/resume lifecycle. A coordinator-owned general plan, search/image/breach/AI checkpoints, sole coordinator ownership and full-stage pause semantics remain incomplete. Pivot diagnostics are persisted and the background pause state is surfaced, while some mature custom resolvers still need migration to the declarative execution path. Crash-boundary tests exercise durable states, but an ADB-driven external process-kill/relaunch and reboot campaign is still required before production recovery is claimed.

## Evidence, graph and entity resolution

- Identity seeds: names, usernames, aliases, emails, phone numbers, locations, organizations and explicit profile URLs.
- Attribution-aware PII extraction.
- Evidence records can retain provider ID, URL, retrieval/observation timestamps, verification state, reliability, SHA-256, parser version and historical/current state.
- Numeric confidence is separate from verification state.
- Graph v2 provides semantic node kinds, typed relationships, evidence IDs, contradiction IDs, history fields and verification/conflict state while retaining saved-case compatibility.
- Multi-signal account resolution is integrated into the production graph.
- A deterministic benchmark harness reports confusion-matrix counts, precision, recall, F1, false-positive rate, false-negative rate and unverifiable-case accuracy over digest-bound corpora.
- Calibration artifacts are schema/version/digest/policy checked; synthetic fixtures cannot activate production policy, and consented artifacts must meet minimum positive/negative sample counts before exact-corpus activation.
- **A shared username alone is not sufficient to confirm identity.**
- Contradictory evidence is preserved.

The shipped synthetic corpus is a regression harness, not representative identity evidence. Entity-resolution weights remain engineering values until a consented representative corpus produces and publishes measured precision, recall, false-positive/false-negative behavior and calibration.

## Reverse-image and visual checks

The selected reference image stays on-device. Dossier may search text/identity clues, download public candidate images, and compare those public images locally.

Implemented whole-image duplicate/repost analysis:

- SHA-256 exact matching;
- pHash, dHash and aHash;
- colour-histogram comparison;
- full-image, centre-crop and square-crop variants;
- exact-copy, near-identical, resized/recompressed and modest-crop detection;
- stable candidate IDs;
- per-candidate provenance containing source, source page, acquisition query, compared URL, retrieval timestamp, content/perceptual hashes, dimensions, comparison score and outcome;
- bounded encrypted case persistence for image results, candidate provenance and duplicate clusters;
- case-load normalization that deduplicates IDs, removes orphan clusters and strips dangling or mislinked references;
- graph enrichment that connects persisted image candidates/clusters to matching public account pages without making identity claims;
- truthful candidate states for unavailable download, decode failure, compared/no-match and match;
- deterministic exact-content and perceptual-near-duplicate clusters with stable IDs;
- Reverse Media UI for candidate-state totals, cluster summaries, hash/dimension details and source drill-down.
- bounded saved-case whole-image cluster history groups repeated fingerprints across saved cases while retaining case/cluster/source/retrieval provenance;
- accessible review wording explicitly states that repeated whole-image content does not establish shared account or person identity.

Whole-image clusters mean **duplicate/reposted image content**. They do not mean two different photos depict the same person.

Optional local cross-photo face support uses pinned YuNet/SFace models with exact size/SHA-256 verification, deterministic preprocessing, five-landmark alignment, ambiguity/quality rejection and cosine scoring. Face similarity remains supporting evidence; release thresholds are not advertised as measured identity probabilities until a representative benchmark exists.

Verified-account correlation and independent visual acceptance remain incomplete. Whole-image similarity and face similarity are supporting evidence, not identity proof.

## Historical evidence

- Bounded exact-URL Wayback lookup and snapshot verification.
- Historical evidence is labeled separately from current evidence.
- Historical confidence is capped.
- Timeline construction uses only real evidence timestamps or provider breach dates.
- Untimestamped observations are omitted rather than assigned fabricated dates.
- Directly re-fetched Wayback HTML is parsed with bounded fail-soft rules for explicit historical display name, bio, username, avatar URL, external links, organization and location attributes. These retain archive reliability, capture timestamps, historical state and semantic attribute labels in the timeline and graph.

Equivalent extraction across every archive/provider source and complete change-diff UX remain incomplete.

## Breach checks

- Pwned Passwords five-character SHA-1 range lookup; the full password is not transmitted.
- HIBP account range lookup when user-supplied supported access is available.
- No silent fallback to sending a complete email address when the privacy-preserving account-range flow is unavailable.
- Authoritative breach coverage remains distinct from general public-web exposure.
- Not-configured, rejected, rate-limited and unavailable states are explicit.
- Breach dates, provider/retrieval metadata and data classes are retained.
- Dossier does not bundle or distribute stolen credential databases or leaked passwords.

## Evidence-grounded AI

- Deterministic local analysis is always available as fallback.
- Optional local/device and user-configured remote engines.
- Retrieved content is treated as untrusted evidence, not instructions.
- Generated factual claims must use structured output and cite existing evidence IDs.
- Claims referring to nonexistent evidence IDs or making uncited factual assertions are rejected.
- Contradiction can downgrade a generated high-confidence claim.
- Malformed/unsupported generated output falls back to deterministic analysis instead of being displayed raw.
- Remote processing remains opt-in and disclosed.
- Remote graph entities and relationships carry bounded pseudonymized references to supporting and contradicting evidence from the same evidence window; raw local evidence IDs do not cross the remote boundary.

A production AI evaluation corpus and fully corrected-graph/remediation-native model inputs remain incomplete.

## Encrypted cases, corrections and remediation

- Active scan state is temporary by default.
- Explicit saves use Android Keystore-backed AES-256-GCM.
- Versioned case schema, atomic writes and integrity verification.
- Legacy plaintext AI keys migrate into encrypted storage; unusable encrypted keys fail closed without a plaintext fallback for new reads.
- Case schema v3 supports authorized scope, scan history, user corrections, remediation records and export records.
- Saved-case comparison uses explicit older/newer roles.
- Evidence decisions: **Mine / Not mine / Unsure / Ignore**.
- Account decisions: **This is me / Not me / Unsure**.
- Corrections affect effective analysis/graph membership without deleting raw encrypted evidence.
- Remediation states: Not started, In progress, Submitted, Awaiting response, Completed, Rejected and Needs manual action.
- Differential comparison classifies added, removed, changed and unchanged findings.
- Recheck UI distinguishes **Still observed**, **Not observed in latest scan**, **Workflow status changed** and **Not rechecked**.
- `Not observed in latest scan` explicitly does **not** mean verified deletion from every live page, search index, cache or archive.

## Reports and share-safe export

- Paginated PDF plus machine-readable JSON evidence package.
- Per-section SHA-256 hashes and canonical manifest hash.
- Explicit `ShareSafe` mode from saved Cases.
- Redaction happens **before export files are written**.
- Share-safe mode removes/generalizes direct subject values, source URLs, snippets, profile details, graph labels/details, breach identifiers, visual source URLs and generated analysis that may reproduce identifying evidence.
- JSON records redaction mode.
- UI warns that redaction reduces disclosure but cannot guarantee anonymity; generated files should be reviewed before sharing.

## Privacy and network behavior

Dossier has no required project-operated backend and does not include analytics telemetry. It is not fully offline.

Network-dependent operations can include public profile/source checks, search/image-index acquisition, archive retrieval, public candidate-image/model downloads, HIBP range queries and optional remote AI.

Local operations include reference-image processing, exact/perceptual image comparison, YuNet/SFace inference, PII parsing, graph/risk analysis, encrypted case and scan-checkpoint state, report generation and share-safe redaction.

The reverse-image matcher and bounded WebView renderer identify themselves generically as Dossier rather than impersonating a consumer browser/device. WebView scraping is cookie-free, blocks non-HTTP(S), cross-host and HTTPS-downgrade top-level navigation, and restores the app's prior cookie setting without clearing the separate evidence browser's global storage. Challenge pages and source restrictions are reported rather than bypassed.

## Build and validation

Requirements:

- JDK 21
- Android SDK 35
- Android Studio or Gradle wrapper
- Android 8.0+

Core checks:

```sh
python3 tools/provider_registry_audit.py
pwsh -File tools/verify_whatsmyname_catalog.ps1
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:connectedUiTestAndroidTest
```

CI separately validates the pinned Python/OpenCV face-calibration environment.

A release build requires private signing properties:

```sh
./gradlew :app:assembleRelease
```

Do not commit `local.properties`, keystores, credentials, API keys, personal test identities, developer-specific paths or real-user screenshots.

## Known limitations

- `ProviderCatalogV2` has 78 authored definitions, while the separate pinned WhatsMyName catalog has 716 records and 644 executable HTTPS username rules; this remains below the 1,000+ useful reviewed-provider target and does not establish live health.
- Private, authenticated, blocked, never-indexed and never-archived content cannot be discovered reliably.
- Providers can change markup, challenge requests, rate-limit or omit content.
- Some custom resolver operations still bypass unified provider lifecycle events; a coordinator-owned general plan and non-profile/image/search/breach/AI checkpoints remain incomplete. Background pause/resume is exact-owner bounded rather than a universal coordinator pause contract.
- Pre-upgrade WorkManager rows may retain legacy raw scan input until WorkManager pruning; new rows are opaque, but no forensic SQLite/WAL erasure claim is made.
- Generation-bound startup reconciliation and cancellation are implemented, but external process-kill/relaunch and reboot validation is not yet recorded.
- Replacement-generation cleanup is best effort after WorkManager enqueue acknowledgement. A crash or cleanup failure in that narrow hand-off can retain an encrypted prior-request profile scope until explicit purge or later maintenance; a durable retirement ledger remains open.
- UI latest-result reads, purge and case-save actions now use IO dispatchers; lower-level synchronous helpers remain for controlled lifecycle paths. Background result envelopes enforce bounded file, metadata, IV, ciphertext and plaintext sizes before allocation/decryption. Large-case ANR/storage-corruption testing remains open.
- Entity resolution has deterministic metrics and a fail-closed calibration-artifact path, but still needs a consented representative corpus and published measured calibration before weights can be treated as empirically fitted.
- Verified-account correlation and richer cross-scan image change workflows remain open; bounded candidate/cluster provenance persistence and saved-case fingerprint history review are implemented.
- Cross-photo face correlation still requires measured ROC/FAR/FRR and representative physical-device validation.
- Historical extraction is currently strongest for directly re-fetched Wayback HTML; archive/provider-wide change extraction remains incomplete.
- HIBP email coverage depends on user-supplied supported access and provider availability.
- Share-safe redaction reduces disclosure but cannot guarantee anonymity.
- Visual QA currently covers one API 36 emulator viewport with synthetic data. The changed scan-budget and bottom-navigation states were checked at 1.0x, 1.3x, 1.5x and 2.0x font scale, but this does not establish whole-product accessibility, landscape/tablet or physical-device acceptance.
- Emulator CI cannot replace Samsung/Pixel/lower-memory, accessibility, font-scale, process-death, thermal, battery and large-case validation.

## Documentation policy

The repository intentionally keeps only three Markdown documents:

- `README.md` — public product/build documentation.
- `AGENTS.md` — authoritative product/engineering contract.
- `TRUTH.md` — authoritative current status, validation record and remaining work.

Do not add separate status, roadmap, audit, handoff, findings or completion Markdown files. Update `TRUTH.md` instead.

## License

Apache License 2.0. See `LICENSE`.
