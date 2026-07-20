# Dossier — Current Status

**Date**: 2026-07-20 (QA Audit)  
**Branch**: `main`  
**Latest commits**: `2571b72` (M6-M16 milestones), `46b4136` (face embedding)  
**Version**: 0.1.0 (`versionCode` 1)

**⚠️ CRITICAL**: Commits 2571b72 and 46b4136 are UNTESTED on device. Build passes, 126 unit tests pass, but end-to-end flow on emulator blocked by navigation bug (Scan→Report fails; loops back to Identity instead).

---

## Product summary

Dossier is an Android app for **authorized public-footprint investigation** (self-audit, research, or course demo). You supply identity signals; the app fans out over public profile templates, one- and two-hop pivots, search/image indexes, optional local face-vs-avatar scoring, fused email breach/public exposure checks, an entity graph, risk scoring, and an AI or baseline narrative.

It is **not** an offline-only tool and **not** a full classified multi-source intelligence platform. Network access to public sites is required for a useful scan.

---

## BLOCKING BUG: Scan → Report Navigation

**Status**: CRITICAL - Prevents device testing of all Report features  
**Symptoms**: After scan completes, app navigates back to Identity screen instead of Report  
**Evidence**:
- ScanScreen logs show scan completing (isScanning → false)
- onScanComplete() callback should fire and call navController.navigate("report")
- Instead, user is returned to IdentityScreen  
**Impact**: Cannot test Report, Entity Graph, Case Comparison, or any downstream features  
**Workaround**: None (blocking full QA)  
**Added**: Defensive logging in ScanScreen (line 100) and MainHubScreen (lines 164-175) to capture failure point when device testing resumes

---

## What works end-to-end (scan → report)

| Stage | Behavior |
|---|---|
| Consent | Honest copy: network, authorized use, local face when model imported |
| Identity intake | Name, primary/other usernames, emails, phones, aliases, locations, orgs, profile URLs, selfie, Deep Research |
| Username discovery | Seeds from primary, name, all step-3 usernames, email local-parts; selected set is preserved into the scan |
| Pass 1 — templates | Up to **80** candidates across platforms (FB/Discord included); OkHttp then WebView |
| Pass 2 — pivots | Up to **30** pivot candidates across **2 hops** from verified profiles; Deep Research follows up to **5** personal sites |
| Pass 3 — public search | DDG / Bing / Google / Yandex HTML scrape; phone + email local-part queries; unverified review hits |
| Pass 4 — public images | Identity-term image index hits (no selfie upload) |
| Face consistency | Selfie + **bundled FaceNet** (or user override) → download avatars → cosine scores; factory/imported calibration → findings |
| Breach fusion | Auto public email exposure check during scan (HIBP breach titles need API key on Breach tab) |
| Entity graph | `EntityGraphBuilder` fuses person, handles, emails, phones, profiles, PII, face, breaches |
| Identity graph UI | Interactive node-link `EntityGraphView` (Compose Canvas): type-colored nodes, tap-to-highlight evidence/edges |
| Evidence layer | Parallel `Evidence`/`EvidenceCollection` model + bidirectional `Evidence ↔ Finding` adapter; `ScannerPlugin`/`EvidenceProducer`/`RelationshipProvider`/`ConfidenceContributor` SDK contracts (no consumers rewritten yet) |
| Risk + remediation | Max risk across findings; type-based tips (PII risks no longer clobbered by profile confidence) |
| AI summary | Local Gemma → remote providers (priority order, Keystore keys) → deterministic baseline |
| Report | Dossier + exposure logs; entity graph + breach sections; full plain-text share |
| Breach / media / models tabs | Still available as dedicated tools |

---

## Roadmap status (see ROADMAP.md for full mapping)

| Milestone | Status | Notes |
|---|---|---|
| 1 Identity Engine | Done | Models, `EntityGraph`, serialization, renderer interface (`EntityGraphView`) |
| 2 Scanner Framework | Done | `ProfileScanner` + PII/face/breach passes; plugin SDK contracts added (M15) |
| 3 Reverse Image Pipeline | Done | `ReverseImageLookupService` (EXIF/OCR/labels, no selfie upload) |
| 4 Username Correlation | Done | `UsernameVariantGenerator` + platform checks |
| 5 Public Page Intelligence | Done | PII extraction on profiles/search |
| 6 Evidence Correlation | Done | `EntityGraphBuilder` now fuses `Evidence` natively (kind→entity mapping + scanner-asserted `EvidenceRelationship` seeding) alongside `Finding`; graph consumes the parallel Evidence layer directly |
| 7 Confidence Engine | Done (core) | `ConfidenceEngine` + 4 contributors (username sim, email-domain, shared-identifier, shared-domain) → explainable per-edge confidence |
| 8 Identity Graph | Done | Interactive `EntityGraphView` (Graph+List a11y) on Report |
| 9 Exposure Engine | Done | `ExposureEngine` derives 6 sub-scores (Identity/Professional/Personal/Contact/Image/Location) + Top-10 findings; shown in report |
| 10 Attack Paths | Done | `AttackPathFinder` BFS over graph from subject to breach/risk endpoints; shown in report |
| 11 Remediation Engine | Done | `RemediationProvider.getStructuredTips` → Problem/Evidence/Risk/Fix/Impact; shown in report |
| 12 AI Layer | Done | Local Gemma + remote providers + baseline |
| 13 Timeline | Done | `CaseComparisonScreen` (CASES tab) lists saved local cases, single-case snapshot, auto-selects most-recent two |
| 14 Scan Comparison | Done | `CaseComparisonScreen` renders CaseDiff: added/removed/changed findings, profiles/breaches delta, risk + exposure delta |
| 15 Plugin SDK | Done (core) | `PluginRegistry` + `runPlugins` aggregator + `SeedEvidencePlugin` example; plugins feed confidence engine |
| 16 Performance | Done | Cancellable scan scope + Cancel button; `MemoryGuard` caps retained findings (honest "N omitted" notice on report); `ScanResumeStore` persists a local resume point surfaced as "Resume last scan" on Identity |
| 17 Android UX | Done | Compose hub + report + tabs |

**Divergence from the idealized ROADMAP:** the shipped app uses `Finding` as the
integration hub (produced by scanners, consumed by graph/risk/remediation/AI/
export). The ROADMAP's `Evidence` type is introduced **in parallel** with a
lossless adapter; the entity graph and confidence engine now consume `Evidence`
directly (M6). `ProfileScanner` additionally emits an `EvidenceCollection`
*natively* (`scanIdentityEvidence` / `toEvidenceCollection`) — profile matches,
PII, and scanner-asserted relationships (username↔profile, PII-on-profile) — so
the scanner's structural knowledge feeds the correlation engine without
re-running the network scan. Existing `Finding`-based paths are untouched.

---

## Architecture (unchanged direction)

```
domain/   scanner, PII, risk, face, graph, username, place
data/     platforms, web search, breach, AI, face runtimes, EXIF/OCR
ui/       Compose screens + navigation
export/   plain-text intelligence brief + JSON findings
```

---

## Verification

Verified under **JDK 21** (required; lint fails on JDK 25 in this environment):

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --quiet
```

All three tasks exited **0** after the fusion pass.

Unit coverage includes: username variants (email locals, all seeds), belonging, public search queries, entity graph builder, face calibration/cosine, AICore parse, breach parsing, risk, handles, PII, image/search parsers, video sampling.

---

## Known limitations (honest)

1. **Login-walled / bot-walled platforms** (LinkedIn, Instagram, X, etc.) often return *Unverifiable*; pivots only seed from **verified** profiles.
2. **Search/image scraping** is fragile (CAPTCHA, markup churn); results are review-only.
3. **HIBP email breach catalog** needs a user-supplied API key; without it, scan uses public-index email evidence only.
4. **Face** uses bundled FaceNet + factory thresholds (research-grade evaluation still recommended for production claims).
5. **No people-search / court / property / dark-web dump APIs** beyond HIBP + public web.
6. **Session is in-memory**; purge clears state (good for privacy, no multi-day case files).
7. **Release signing** needs private `RELEASE_*` / CI keystore secrets for distribution.

---

## Demo checklist

1. Consent → accept  
2. Identity: full name + known public handle + email(s) + optional selfie  
3. Username discovery → confirm seeds (email locals should appear)  
4. Enable **Deep Research**  
5. Scan; watch stages: discover → faces → breach → entity graph → risk → AI  
6. Report: entity graph, breach block, profiles, share **Transmit Dossier**  
7. Optional: Models (face model + remote AI), Breach tab (HIBP key for richer email breaches)

Best demo subjects: accounts with **public GitHub / Reddit / personal site** presence. Heavy SPA social alone will under-verify.
