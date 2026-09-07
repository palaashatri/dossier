# Task 2 report — product-backed typed-frontier benchmark

## Status

DONE_WITH_CONCERNS

The requested benchmark is implemented as a network-free JVM test over the
real `ProfileScanner.runTypedSeedFrontier` path. It is not a live-provider or
device acceptance test; all retrieval times and provider responses are fixed
synthetic fixtures.

## Base, head, and commit

- Branch: `codex/discovery-followup`
- Base before Task 2: `7c7a1bc2388d7edc94a5a26b5e1f53f7c1e389c6`
- Head / implementation commit: `babeb61` (`test: add product-backed typed frontier benchmark`)
- No push performed.

## Files changed

- `app/src/test/java/io/dossier/app/domain/scanner/ProductTypedFrontierBenchmarkTest.kt`
  - Adds a synthetic URL cascade, email-search replay, phone-provider-failure,
    and unsupported Photo/Image benchmark over the product scanner and executor
    seams.
- `.superpowers/sdd/dossier-discovery-followup-plan/task-2-report.md`
  - This evidence report (the `.superpowers` tree is repository-ignored and is
    force-added only as the requested task artifact).

No production source was changed.

## Design and data flow

Each fixture supplies one attacker seed through `IdentityInput`, with no name,
alias, location, organization, username, or selfie enrichment. `runCase`
injects `TypedSeedPublicFetchExecutor` seams for direct fetches, archive-aware
fetches, and public-search outcomes, then runs the real rolling typed frontier.
The resulting `EvidenceCollection` is converted into `DiscoveryEvent` values;
event status comes from product `EvidenceState`, request counts are assigned
from actual fetched/search requests, and timestamps come only from fixture
source-time maps. The event list is passed to `DiscoveryBenchmark.evaluate` and
then `DiscoveryBenchmark.aggregate`.

The URL fixture exercises URL → Domain/Document/Archive and extracts exact
email and phone observations, including one missing URL that remains
`UNAVAILABLE` and the archive host known-negative that exercises false-positive
accounting. The email fixture exercises a directly verified profile replay,
recursively discovered document and phone, plus an unrelated candidate. The
phone fixture returns a provider outage as `PROVIDER_FAILURE`. Photo and Image
are asserted as explicitly unsupported/skipped and never reach either injected
network seam.

## Required RED evidence

The initial scaffold assertion was intentionally failing before the benchmark
was wired to the product path:

```text
./gradlew :app:testDebugUnitTest --tests io.dossier.app.domain.scanner.ProductTypedFrontierBenchmarkTest
ProductTypedFrontierBenchmarkTest > productBackedTypedFrontierMeasuresRecursiveCorpus FAILED
java.lang.AssertionError at ProductTypedFrontierBenchmarkTest.kt:20
```

During the fix loop, the product-backed test also exposed and corrected four
fixture/accounting assumptions: recursive admission of the email search host,
same-source timing for the replayed document, concurrent fetch ordering, and
the aggregate known-negative denominator. The final focused test was rerun
after each correction.

## GREEN validation

1. `./gradlew :app:testDebugUnitTest --tests io.dossier.app.domain.scanner.ProductTypedFrontierBenchmarkTest --no-daemon --console=plain`

   `BUILD SUCCESSFUL in 8s`; the XML result recorded 1 test, 0 failures, 0
   errors, and 0 skipped after the final revert.

2. `./gradlew :app:testDebugUnitTest --tests io.dossier.app.domain.scanner.ProductTypedFrontierBenchmarkTest --tests io.dossier.app.data.web.TypedSeedPublicFetchBenchmarkTest --tests io.dossier.app.domain.scanner.ProfileScannerTypedFrontierRegressionTest --no-daemon --console=plain`

   `BUILD SUCCESSFUL in 41s`; XML results recorded 9 tests total: 1 new
   product benchmark, 1 existing typed-seed benchmark, and 7 frontier
   regression tests, all with 0 failures, 0 errors, and 0 skipped.

3. The staged implementation passed `git diff --cached --check` before commit.

Gradle emitted only existing SDK XML/deprecation warnings; no test or compile
error was reported.

## Hand-derived fixture metrics

| Case | TP / FP / FN | Recall | Precision | Candidates | Unavailable | Provider failures | Requests | Simulated timing |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| URL cascade | 5 / 1 / 0 | 1.0 | 5/6 | 0 | 1 | 0 | 7 | useful/high-value/50%/80% = 100 ms; duration 320 ms |
| Email search | 3 / 0 / 0 | 1.0 | 1.0 | 1 | 0 | 0 | 3 | useful/anchor/high-value/50%/80% = 80 ms; duration 160 ms |
| Phone failure | 0 / 0 / 1 | 0.0 | 1.0 (empty-positive denominator) | 0 | 0 | 1 | 1 | duration 40 ms |

The aggregate assertions are: 3 cases; 8 TP, 1 FP, 1 FN; corpus precision and
Recall@known-exposure both `8/9`; average recall `2/3`; corpus known-negative
false-positive rate `0.5` (one verified negative over two labelled negatives);
3 unresolved events, 1 candidate, 1 unavailable, 1 provider failure; 11
requests, 2 failed requests, provider failure rate `2/11`; and 90 ms average
time to first useful/high-value result across the two cases that produced one.
The URL and email cases both reach a verified identity anchor.

All timing above is deterministic simulated fixture timing, never wall-clock or
live-network acceptance.

## Mutation check

The aggregate TP assertion was temporarily mutated from `8` to `7`. The focused
test correctly failed with:

```text
java.lang.AssertionError: expected:<7> but was:<8>
```

The mutation was immediately reverted, and the final focused GREEN command was
rerun. This verifies that the benchmark assertions detect a material metric
regression.

## `agy` outcome

`agy` was attempted first with a bounded Gemini high-effort implementation
prompt, as required by the repository contract. It terminated with:

```text
Error: Agent execution terminated due to error.
```

It made no edits. The test was completed directly after that failure.

## Self-review

- The test drives production frontier orchestration rather than a parallel
  fixture walker and derives events from the returned `EvidenceCollection`.
- Exact values, discovery paths, recursive pivots, attacker-seed exclusion,
  status separation, unsupported kinds, and aggregate metrics are asserted.
- Concurrent product scheduling is not falsely treated as ordered: the email
  request assertion checks the exact two-request set and count.
- Only synthetic `.test` URLs, reserved-domain email values, and fixed fixture
  timestamps are used; no private benchmark identity is present.
- The implementation commit contains only the requested test file. The report
  is the requested ignored task artifact and does not alter canonical product
  documents.

## Concerns

- This benchmark proves deterministic product wiring and metric behavior, not
  live source reachability, provider health, network politeness, or physical
  device behavior.
- Photo and Image remain intentionally unavailable in the current executor;
  the test makes that limitation explicit rather than claiming media coverage.
- The simulated corpus is small and hand-authored. It must not be presented as
  representative production recall or as a mission-readiness score.
