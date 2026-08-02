# Dossier Engineering Instructions

This file is the operational contract for maintainers and coding agents working in this repository.

## Mission

Build a consent-first Android privacy-audit application that helps an authorized user understand publicly exposed information, verify evidence, connect related signals, and reduce exposure.

The product must remain evidence-oriented. Search results, visual similarity, extracted identifiers, and graph relationships are leads with provenance and confidence—not automatic proof of identity, ownership, intent, or current activity.

## Documentation rule

The repository has exactly three Markdown documents:

- `README.md` — public product and build documentation.
- `AGENTS.md` — engineering rules and working instructions.
- `TRUTH.md` — authoritative implementation status, audit record, roadmap, and open limitations.

Never create additional status, roadmap, audit, findings, completion, handoff, planning, or progress Markdown files. Update `TRUTH.md` instead. Temporary notes belong outside the repository.

## Privacy rule

Never commit:

- A real person's name, username, email address, phone number, address, employer, school, profile URL, or other identity fixture.
- Developer-specific absolute paths such as `/Users/name/...` or `C:\Users\name\...`.
- Screenshots containing real identity data, notifications, account names, tokens, or device identifiers.
- API keys, HIBP credentials, remote-provider credentials, signing keys, keystores, cookies, or session tokens.
- Face images, embeddings, calibration subjects, private datasets, or benchmark manifests containing real identities.

Use obviously synthetic fixtures such as `Jane Example`, `sample_user`, `jane@example.test`, and reserved documentation domains.

Before every pull request is considered complete, search at minimum for:

```text
real contributor names
known usernames
@ and email-like fixtures
/Users/
C:\Users\
sk-
api_key
Authorization:
Bearer 
```

Do not rewrite Git history unless the repository owner explicitly requests it. Removing data from the current tree does not remove it from existing commits, forks, caches, release artifacts, or search indexes.

## Safety and authorization

Dossier is for self-audits, consenting subjects, and other legitimate authorized research. Do not add features designed for covert tracking, mass targeting, account compromise, bypassing authentication, or collecting private/non-public information.

Public evidence must retain source provenance and review state. Historical evidence must be labeled historical. Visual correlation must remain supporting evidence and must not assert identity or ownership automatically.

## Architecture

Primary source tree:

```text
app/src/main/java/io/dossier/app/
  data/      External services, local models, persistence adapters
  domain/    Models, evidence, scanners, correlation, risk, remediation
  export/    PDF and JSON evidence packages
  ui/        Compose navigation, screens, components, and theme
```

Dependency direction:

- `domain` must not depend on Android UI code.
- `data` implements infrastructure needed by `domain` workflows.
- `ui` consumes domain state and invokes domain/application actions.
- Export code must not silently weaken evidence semantics.

Keep network acquisition, identity attribution, confidence, risk, and presentation as separate concepts.

## Evidence invariants

1. Every external claim needs a source URL or an explicit locally-derived/self-supplied provenance label.
2. Confidence describes attribution support; risk describes potential impact. Never use one as the other.
3. Search snippets alone cannot create a verified profile.
4. A 404, challenge, timeout, or unavailable provider is not a confirmed absence.
5. Historical archive evidence cannot prove current activity.
6. Reverse-image and face scores cannot prove account ownership.
7. Empty results must say “not found in inspected sources,” never “does not exist.”
8. HIBP authoritative coverage must remain separate from public-web exposure.
9. AI output must disclose its engine and network use and must treat retrieved content as untrusted data.
10. New persistence must be encrypted and must not fall back to plaintext.

## Network and resource rules

- Use strict connect/read/call timeouts.
- Bound response sizes before fully buffering data.
- Validate scheme, host, redirect target, content type, and expected file length where applicable.
- Honor cancellation. Prefer coroutine-cancellable asynchronous calls over long blocking calls.
- Bound provider concurrency and request budgets.
- Apply retries only where safe and respect `Retry-After`.
- Keep provider failures isolated so one source cannot invalidate the entire report.
- Do not submit URLs or user data to archives automatically.

## Visual-correlation rules

- The selected reference image remains local.
- Strong local correlation requires an explicit per-scan choice.
- Model files must be pinned by cryptographic hash and expected size.
- Before strong inference, verify the active model pack and calibration binding.
- Reject ambiguous group photos and poor-quality inputs rather than forcing a score.
- Release transient matrices, crops, landmarks, and embeddings promptly.
- Reference thresholds remain manual-review only.
- Measured thresholds must be identity-disjoint, held-out, hash-bound, reproducible, and documented in `TRUTH.md`.
- Never bundle a private face dataset.

## UI/UX rules

- Prefer calm privacy-audit language over surveillance, military, or theatrical terminology.
- Every destructive action requires clear scope and confirmation.
- Keep risk and confidence visually distinct.
- Use explicit verified, review, unavailable, historical, and not-found states.
- Support small screens, large font scales, TalkBack, switch access, keyboard navigation, and reduced motion.
- Use at least 48dp interactive targets unless a platform component provides an equivalent accessible target.
- Do not use color as the only status signal.
- JavaScript, persistent web storage, file access, and mixed content stay disabled in the evidence viewer unless a narrowly reviewed requirement justifies enabling them.
- Use Android system pickers instead of broad media-library permissions.

## Required verification

Run before concluding a change:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For face-calibration changes, also validate the pinned Python/OpenCV toolchain and calibration script used by CI.

Add regression tests for every corrected parsing, attribution, lifecycle, persistence, export, or evidence-semantic defect.

Compilation is necessary but not sufficient. Changes affecting Compose layout, permissions, WebView, camera, Photo Picker, local models, accessibility, process recreation, or performance require real-device or instrumented validation recorded in `TRUTH.md`.

## Completion standard

A task is complete only when:

- The implementation is present and connected to the user flow.
- Error, unavailable, cancellation, empty, and destructive states are handled.
- Privacy and evidence semantics remain truthful.
- Tests and debug APK assembly pass.
- Documentation is updated without creating a new Markdown file.
- No real personal information or credentials were introduced.
- Remaining external or empirical limitations are stated in `TRUTH.md`.

Never claim 10/10 coverage, identity accuracy, privacy, accessibility, or production readiness without measured evidence supporting that exact claim.
