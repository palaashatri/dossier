# Dossier — Current Status

**Date**: 2026-07-11  
**Branch**: `main`  
**Latest commits**: `1bc48f5` (dossier fusion), `78842bc` (face consistency)  
**Version**: 0.1.0 (`versionCode` 1)

---

## Product summary

Dossier is an Android app for **authorized public-footprint investigation** (self-audit, research, or course demo). You supply identity signals; the app fans out over public profile templates, one- and two-hop pivots, search/image indexes, optional local face-vs-avatar scoring, fused email breach/public exposure checks, an entity graph, risk scoring, and an AI or baseline narrative.

It is **not** an offline-only tool and **not** a full classified multi-source intelligence platform. Network access to public sites is required for a useful scan.

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
| Risk + remediation | Max risk across findings; type-based tips (PII risks no longer clobbered by profile confidence) |
| AI summary | Local Gemma → remote providers (priority order, Keystore keys) → deterministic baseline |
| Report | Dossier + exposure logs; entity graph + breach sections; full plain-text share |
| Breach / media / models tabs | Still available as dedicated tools |

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
