package io.dossier.app.data.web

import io.dossier.app.data.web.DiscoveryBenchmark.DiscoveryEvent
import io.dossier.app.data.web.DiscoveryBenchmark.EventStatus
import io.dossier.app.data.web.DiscoveryBenchmark.Fact
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticCase
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticRun

/**
 * Checked-in, reserved-value corpus for recursive discovery regression tests.
 * It is intentionally test-only: these values are not production benchmark data.
 */
object SyntheticDiscoveryBenchmarkFixtures {
    data class Transition(
        val from: Fact,
        val event: DiscoveryEvent
    ) {
        val to: Fact
            get() = event.fact
    }

    data class CaseFixture(
        val id: String,
        val case: SyntheticCase,
        val requiredPath: List<Fact>,
        val transitions: List<Transition>
    )

    data class Trace(
        val run: SyntheticRun,
        val traversed: List<Transition>
    )

    /** Deterministic breadth-first discoverer; it never performs network I/O. */
    class Discoverer(
        private val fixtures: List<CaseFixture> = corpus()
    ) {
        fun discover(case: SyntheticCase): SyntheticRun = trace(case).run

        fun trace(case: SyntheticCase): Trace {
            val fixture = fixtures.single { it.case == case }
            val remaining = fixture.transitions
            val frontier = ArrayDeque<Fact>().apply { addLast(case.initialSeed) }
            val visited = mutableSetOf<String>()
            val traversed = mutableListOf<Transition>()

            while (frontier.isNotEmpty()) {
                val source = frontier.removeFirst()
                if (!visited.add(key(source))) continue
                remaining
                    .filter { key(it.from) == key(source) }
                    .forEach { transition ->
                        traversed += transition
                        if (
                            transition.event.status == EventStatus.VERIFIED &&
                            transition.to.normalizedValue.isNotBlank()
                        ) {
                            frontier.addLast(transition.to)
                        }
                    }
            }

            val events = traversed.map(Transition::event)
            return Trace(
                run = SyntheticRun(
                    events = events,
                    totalScanDurationMs = events.maxOfOrNull(DiscoveryEvent::elapsedTimeMs) ?: 0L,
                    totalRequestCount = events.sumOf(DiscoveryEvent::requestCount)
                ),
                traversed = traversed
            )
        }
    }

    /** New values are reachable only through the explicit transition below. */
    fun corpus(): List<CaseFixture> = listOf(
        nameToContact(),
        usernameToArchive(),
        photoToSourcePage()
    )

    private fun nameToContact(): CaseFixture {
        val seed = Fact("name", "Jane Example")
        val profile = Fact("profile", "https://example.test/profile/jane-example")
        val email = Fact("email", "jane@example.test")
        val document = Fact("document", "https://example.test/docs/jane-example-cv.pdf")
        val phone = Fact("phone", "+1 555 0100")
        val candidatePhone = Fact("phone", "+1 555 0111")
        val knownNegativePhone = Fact("phone", "+1 555 0199")
        val extraOrganization = Fact("organization", "Example Labs")
        return CaseFixture(
            id = "name-profile-email-document-phone",
            case = SyntheticCase(
                name = "name-profile-email-document-phone",
                initialSeed = seed,
                expectedFacts = listOf(profile, email, document, phone),
                knownNegatives = listOf(knownNegativePhone),
                isCompleteGroundTruth = false
            ),
            requiredPath = listOf(seed, profile, email, document, phone),
            transitions = listOf(
                transition(
                    from = seed,
                    to = profile,
                    elapsedTimeMs = 100,
                    providerId = "synthetic-search",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                transition(
                    from = profile,
                    to = email,
                    elapsedTimeMs = 200,
                    providerId = "synthetic-profile",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = email,
                    to = Fact("phone", null),
                    elapsedTimeMs = 250,
                    status = EventStatus.UNAVAILABLE,
                    providerId = "synthetic-exposure-index",
                    requestCount = 1
                ),
                transition(
                    from = email,
                    to = document,
                    elapsedTimeMs = 300,
                    providerId = "synthetic-document-search",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = profile,
                    to = extraOrganization,
                    elapsedTimeMs = 350,
                    providerId = "synthetic-profile",
                    usefulPivotCount = 1
                ),
                transition(
                    from = document,
                    to = candidatePhone,
                    elapsedTimeMs = 400,
                    status = EventStatus.CANDIDATE,
                    providerId = "synthetic-document-parser"
                ),
                transition(
                    from = document,
                    to = knownNegativePhone,
                    elapsedTimeMs = 450,
                    providerId = "synthetic-directory",
                    requestCount = 1
                ),
                transition(
                    from = document,
                    to = phone,
                    elapsedTimeMs = 500,
                    providerId = "synthetic-document-parser"
                )
            )
        )
    }

    private fun usernameToArchive(): CaseFixture {
        val seed = Fact("username", "archive_handle")
        val website = Fact("website", "https://example.test/archive-handle")
        val archive = Fact(
            "archive",
            "https://archive.example.test/snapshots/archive-handle-20250101"
        )
        return CaseFixture(
            id = "username-website-archive",
            case = SyntheticCase(
                name = "username-website-archive",
                initialSeed = seed,
                expectedFacts = listOf(website, archive),
                isCompleteGroundTruth = true
            ),
            requiredPath = listOf(seed, website, archive),
            transitions = listOf(
                transition(
                    from = seed,
                    to = website,
                    elapsedTimeMs = 120,
                    providerId = "synthetic-website-search",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                transition(
                    from = website,
                    to = archive,
                    elapsedTimeMs = 240,
                    providerId = "synthetic-archive",
                    requestCount = 1
                )
            )
        )
    }

    private fun photoToSourcePage(): CaseFixture {
        val seed = Fact("photo", "https://example.test/photos/example-plaza.jpg")
        val ocr = Fact("ocr", "Example Plaza")
        val location = Fact("location", "Example City")
        val sourcePage = Fact("source-page", "https://example.test/photos/example-plaza-source")
        val visualCandidate = Fact("location", "Example County")
        return CaseFixture(
            id = "photo-ocr-location-source-page",
            case = SyntheticCase(
                name = "photo-ocr-location-source-page",
                initialSeed = seed,
                expectedFacts = listOf(ocr, location, sourcePage),
                isCompleteGroundTruth = false
            ),
            requiredPath = listOf(seed, ocr, location, sourcePage),
            transitions = listOf(
                transition(
                    from = seed,
                    to = ocr,
                    elapsedTimeMs = 80,
                    providerId = "synthetic-local-ocr",
                    requestCount = 0,
                    usefulPivotCount = 1
                ),
                transition(
                    from = seed,
                    to = visualCandidate,
                    elapsedTimeMs = 90,
                    status = EventStatus.CANDIDATE,
                    providerId = "synthetic-visual-cue"
                ),
                transition(
                    from = ocr,
                    to = location,
                    elapsedTimeMs = 160,
                    providerId = "synthetic-location-resolver",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = location,
                    to = Fact("source-page", null),
                    elapsedTimeMs = 220,
                    status = EventStatus.UNAVAILABLE,
                    providerId = "synthetic-reverse-image",
                    requestCount = 1
                ),
                transition(
                    from = location,
                    to = sourcePage,
                    elapsedTimeMs = 280,
                    providerId = "synthetic-source-page-search",
                    requestCount = 1
                )
            )
        )
    }

    private fun transition(
        from: Fact,
        to: Fact,
        elapsedTimeMs: Long,
        status: EventStatus = EventStatus.VERIFIED,
        providerId: String,
        requestCount: Int = 0,
        isIdentityAnchor: Boolean = false,
        usefulPivotCount: Int = 0
    ): Transition = Transition(
        from = from,
        event = DiscoveryEvent(
            elapsedTimeMs = elapsedTimeMs,
            fact = to,
            status = status,
            providerId = providerId,
            requestCount = requestCount,
            isIdentityAnchor = isIdentityAnchor,
            usefulPivotCount = usefulPivotCount
        )
    )

    private fun key(fact: Fact): String =
        "${fact.normalizedKind}:${fact.normalizedValue}"
}
