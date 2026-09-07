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
        photoToSourcePage(),
        photoToReverseImageLocation(),
        photoToFaceAccount(),
        emailToExposure()
    )

    private fun nameToContact(): CaseFixture {
        val seed = Fact("name", "Jane Example")
        val profile = Fact("profile", "https://example.test/profile/jane-example")
        val username = Fact("username", "jane-example")
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
                expectedFacts = listOf(profile, username, email, document, phone),
                knownNegatives = listOf(knownNegativePhone),
                isCompleteGroundTruth = false
            ),
            requiredPath = listOf(seed, profile, username, email, document, phone),
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
                    to = username,
                    elapsedTimeMs = 180,
                    providerId = "synthetic-profile",
                    isIdentityAnchor = true,
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = username,
                    to = email,
                    elapsedTimeMs = 220,
                    providerId = "synthetic-profile-search",
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
        val profile = Fact("profile", "https://example.test/profile/archive-handle")
        val website = Fact("website", "https://example.test/archive-handle")
        val email = Fact("email", "archive@example.test")
        val archive = Fact(
            "archive",
            "https://archive.example.test/snapshots/archive-handle-20250101"
        )
        return CaseFixture(
            id = "username-website-archive",
            case = SyntheticCase(
                name = "username-website-archive",
                initialSeed = seed,
                expectedFacts = listOf(profile, website, email, archive),
                isCompleteGroundTruth = true
            ),
            requiredPath = listOf(seed, profile, website, email, archive),
            transitions = listOf(
                transition(
                    from = seed,
                    to = profile,
                    elapsedTimeMs = 120,
                    providerId = "synthetic-website-search",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                transition(
                    from = profile,
                    to = website,
                    elapsedTimeMs = 240,
                    providerId = "synthetic-profile-parser",
                    usefulPivotCount = 1
                ),
                transition(
                    from = website,
                    to = email,
                    elapsedTimeMs = 300,
                    providerId = "synthetic-website-parser",
                    usefulPivotCount = 1
                ),
                transition(
                    from = email,
                    to = archive,
                    elapsedTimeMs = 420,
                    providerId = "synthetic-archive",
                    requestCount = 1,
                    usefulPivotCount = 1
                )
            )
        )
    }

    private fun photoToSourcePage(): CaseFixture {
        val seed = Fact("photo", "https://example.test/photos/example-plaza.jpg")
        val ocr = Fact("ocr", "Example Plaza")
        val location = Fact("location", "Example City")
        val sourcePage = Fact("source-page", "https://example.test/photos/example-plaza-source")
        val profile = Fact("profile", "https://example.test/profile/example-plaza-photo")
        val visualCandidate = Fact("location", "Example County")
        return CaseFixture(
            id = "photo-ocr-location-source-page",
            case = SyntheticCase(
                name = "photo-ocr-location-source-page",
                initialSeed = seed,
                expectedFacts = listOf(ocr, location, sourcePage, profile),
                isCompleteGroundTruth = false
            ),
            requiredPath = listOf(seed, ocr, location, sourcePage, profile),
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
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = sourcePage,
                    to = profile,
                    elapsedTimeMs = 360,
                    providerId = "synthetic-source-page-parser",
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                )
            )
        )
    }

    private fun photoToReverseImageLocation(): CaseFixture {
        val seed = Fact("photo", "https://example.test/photos/example-river.jpg")
        val reverseImage = Fact(
            "reverse-image",
            "https://example.test/reverse/example-river"
        )
        val location = Fact("location", "Example Harbor")
        val profile = Fact("profile", "https://example.test/profile/river-photo")
        val visualCandidate = Fact("location", "Example County")
        return CaseFixture(
            id = "photo-reverse-image-location-profile",
            case = SyntheticCase(
                name = "photo-reverse-image-location-profile",
                initialSeed = seed,
                expectedFacts = listOf(reverseImage, location, profile),
                isCompleteGroundTruth = true
            ),
            requiredPath = listOf(seed, reverseImage, location, profile),
            transitions = listOf(
                transition(
                    from = seed,
                    to = Fact("reverse-image", null),
                    elapsedTimeMs = 50,
                    status = EventStatus.PROVIDER_FAILURE,
                    providerId = "synthetic-tineye",
                    requestCount = 1
                ),
                transition(
                    from = seed,
                    to = reverseImage,
                    elapsedTimeMs = 70,
                    providerId = "synthetic-reverse-image",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = reverseImage,
                    to = location,
                    elapsedTimeMs = 140,
                    providerId = "synthetic-location-resolver",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = reverseImage,
                    to = visualCandidate,
                    elapsedTimeMs = 150,
                    status = EventStatus.CANDIDATE,
                    providerId = "synthetic-visual-cue"
                ),
                transition(
                    from = location,
                    to = profile,
                    elapsedTimeMs = 220,
                    providerId = "synthetic-profile-correlation",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                )
            )
        )
    }

    private fun photoToFaceAccount(): CaseFixture {
        val seed = Fact("photo", "https://example.test/photos/synthetic-face.jpg")
        val faceCandidate = Fact("face-candidate", "synthetic-face-jane")
        val account = Fact("account", "https://example.test/account/jane-example")
        val username = Fact("username", "jane-face")
        val email = Fact("email", "jane.face@example.test")
        val document = Fact("document", "https://example.test/docs/jane-face.txt")
        val unrelatedProfile = Fact("profile", "https://unrelated.test/profile/jane")
        return CaseFixture(
            id = "photo-face-account-username-email-document",
            case = SyntheticCase(
                name = "photo-face-account-username-email-document",
                initialSeed = seed,
                expectedFacts = listOf(faceCandidate, account, username, email, document),
                knownNegatives = listOf(unrelatedProfile),
                isCompleteGroundTruth = false
            ),
            requiredPath = listOf(seed, faceCandidate, account, username, email, document),
            transitions = listOf(
                transition(
                    from = seed,
                    to = faceCandidate,
                    elapsedTimeMs = 60,
                    providerId = "synthetic-local-face",
                    usefulPivotCount = 1
                ),
                transition(
                    from = faceCandidate,
                    to = account,
                    elapsedTimeMs = 130,
                    providerId = "synthetic-face-correlation",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                transition(
                    from = faceCandidate,
                    to = unrelatedProfile,
                    elapsedTimeMs = 180,
                    providerId = "synthetic-profile-candidate",
                    requestCount = 1
                ),
                transition(
                    from = account,
                    to = username,
                    elapsedTimeMs = 200,
                    providerId = "synthetic-account-parser",
                    usefulPivotCount = 1
                ),
                transition(
                    from = username,
                    to = email,
                    elapsedTimeMs = 260,
                    providerId = "synthetic-username-search",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = email,
                    to = document,
                    elapsedTimeMs = 340,
                    providerId = "synthetic-document-search",
                    requestCount = 1
                )
            )
        )
    }

    private fun emailToExposure(): CaseFixture {
        val seed = Fact("email", "contact@example.test")
        val exposure = Fact("exposure", "synthetic-exposure-contact-2025")
        val profile = Fact("profile", "https://example.test/profile/contact")
        val document = Fact("document", "https://example.test/docs/contact-directory.txt")
        val phone = Fact("phone", "+1 555 0123")
        val unrelatedProfile = Fact("profile", "https://unrelated.test/profile/contact")
        return CaseFixture(
            id = "email-exposure-profile-document-phone",
            case = SyntheticCase(
                name = "email-exposure-profile-document-phone",
                initialSeed = seed,
                expectedFacts = listOf(exposure, profile, document, phone),
                knownNegatives = listOf(unrelatedProfile),
                isCompleteGroundTruth = false
            ),
            // The exposure index fans out to both a public profile and a
            // document; the required linear chain follows the document branch
            // while the profile branch remains an expected recovered fact.
            requiredPath = listOf(seed, exposure, document, phone),
            transitions = listOf(
                transition(
                    from = seed,
                    to = Fact("exposure", null),
                    elapsedTimeMs = 40,
                    status = EventStatus.UNAVAILABLE,
                    providerId = "synthetic-exposure-index",
                    requestCount = 1
                ),
                transition(
                    from = seed,
                    to = exposure,
                    elapsedTimeMs = 80,
                    providerId = "synthetic-exposure-index",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = exposure,
                    to = profile,
                    elapsedTimeMs = 140,
                    providerId = "synthetic-public-search",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                transition(
                    from = exposure,
                    to = document,
                    elapsedTimeMs = 180,
                    providerId = "synthetic-document-search",
                    requestCount = 1,
                    usefulPivotCount = 1
                ),
                transition(
                    from = document,
                    to = phone,
                    elapsedTimeMs = 260,
                    providerId = "synthetic-document-parser"
                ),
                transition(
                    from = document,
                    to = unrelatedProfile,
                    elapsedTimeMs = 300,
                    providerId = "synthetic-directory",
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
