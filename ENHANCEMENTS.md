# Dossier — Recommended Enhancements

Prioritized ideas for after the 0.1.0 demo baseline. Ordered by impact vs effort for product quality and academic demos.

---

## P0 — Highest demo / correctness impact

| Enhancement | Why | Notes |
|---|---|---|
| **Persist optional HIBP API key (encrypted)** | Scan-time breach fusion currently has no key → public-index only | Mirror `AiProviderConfigStore` Keystore pattern; never log the key |
| **Soft-verify path for SPA profiles** | Many real profiles die at challenge walls | Cache “exists but unverifiable”; allow Deep Research to still open user-supplied URLs |
| **Disambiguation UI for common names** | Homonyms flood false candidates | Cluster by co-occurring email/handle/org; “reject / promote” per candidate |
| **Instrumented smoke test on emulator** | Catch UI/nav regressions before demos | Maestro/Espresso: consent → identity sample → scan cancel → report |
| **Production face calibration pack** | Face findings never elevate risk without JSON | Ship docs + optional sample thresholds only for a *known* bundled/eval model SHA |

---

## P1 — Stronger dossier depth

| Enhancement | Why | Notes |
|---|---|---|
| **3+ hop pivots with decay** | Graph depth for link analysis demos | Confidence decay per hop; hard cap + cycle detection |
| **Email OSINT pack** | Gravatar hash, GitHub commit search, domain WHOIS-lite | Pure public sources; no dump sites |
| **Phone OSINT pack** | Format variants, search paste/index queries, carrier-agnostic | Stay legal/public; no paid lookup without user key |
| **Archive.org / snapshot probes** | Deleted bios and old handles | Bounded URLs from confirmed profiles only |
| **Official APIs where user provides tokens** | GitHub PAT, Mastodon token | Optional; clearer than scraping |
| **Unified `DossierCase` model** | One object for export/PDF | profiles + findings + graph + breach + face + media |

---

## P2 — UX and presentation (professor-facing)

| Enhancement | Why | Notes |
|---|---|---|
| **Visual entity graph** | Force-directed or layered list with filters | Compose Canvas or simple adjacency list with types colored |
| **Source confidence legend** | Verified vs search vs soft existence | Badges already partial; make consistent everywhere |
| **PDF / Markdown export** | Better hand-in than plain text share | On-device template |
| **Scan progress by platform** | “GitHub 12/12, Reddit…” | Better live demo narrative |
| **Saved demo profile presets** | One-tap load of a *public* sample identity | Opt-in assets only; no real PII |
| **Dark/light report theme toggle** | Print / projector readability | Material 3 schemes |

---

## P3 — Platform and verification engineering

| Enhancement | Why | Notes |
|---|---|---|
| **Per-platform parsers** | GitHub JSON/API, Reddit `.json`, HN API | Higher quality than generic HTML |
| **Mastodon multi-instance** | Today: `mastodon.social` only | Parse `user@instance` |
| **Stack Overflow numeric ID resolution** | Slug form is wrong for many users | Search → resolve ID |
| **Longer / adaptive WebView budget** | 14s may still fail slow SPAs | Per-platform timeouts |
| **Headless Chrome remote fallback** | Optional user-run sidecar for hard walls | Advanced; out of pure Android path |
| **Rate-limit + polite crawl policy** | Avoid ban during class demos | Global QPS, robots-aware where applicable |

---

## P4 — AI and vision

| Enhancement | Why | Notes |
|---|---|---|
| **AICore device validation matrix** | Pixel-class AICore behavior is untested here | Status / download / latency / quality |
| **True multimodal import path** | MediaPipe path is classifier/detector labels only | Document AICore as VLM; optional remote vision API |
| **Structured AI output** | JSON schema for “claims + evidence URLs” | Reduces hallucinated summaries |
| **Offline Gemma default path polish** | Large downloads fail mid-way | Resume, disk checks, cancel |

---

## P5 — Security, privacy, compliance

| Enhancement | Why | Notes |
|---|---|---|
| **Purpose binding + audit log** | Academic integrity | “Why am I scanning?” purpose string stored with export |
| **Redaction before share** | Strip high-risk fields on demand | Toggle for presentation mode |
| **Encrypted session export** | Optional case file on disk | User passphrase |
| **Clear data inventory screen** | What’s stored where | Models, keys, cache dirs |

---

## P6 — Hard “full intelligence dossier” (scope expansion)

Only pursue with clear legal/ethical framing:

- Public records / court / business registries (jurisdiction-specific APIs)
- Multi-session investigation graph DB
- Social graph mutuals / co-followers (API tokens)
- True reverse face search (external service; high risk / policy)
- Dark-web / stealer-log markets (generally out of scope for this app)

---

## Suggested next sprint (2–3 days)

1. Encrypted optional HIBP key + wire into scan fusion  
2. Per-platform GitHub + Reddit JSON enrichment  
3. Visual entity graph on Report  
4. Emulator smoke test script  
5. One public demo preset (e.g. well-known open-source maintainers’ *public* handles only)

---

## Non-goals (keep explicit)

- Fabricating profile hits or face scores when data is missing  
- Silent mass enumeration without caps  
- Shipping private keystores or API keys in the repo  
- Claiming definitive identity from search-only evidence  
