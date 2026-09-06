package io.dossier.app.domain.analysis

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
enum class PresenceState { Exists, SuspiciousSimilarity, NoMatch, Unavailable }

@Serializable
data class SurfacePresence(
    val platform: String,
    val username: String,
    val url: String,
    val state: PresenceState,
    val confidence: Double,
    val reason: String
)

@Serializable
data class IdentitySurfaceMap(
    val entries: List<SurfacePresence> = emptyList(),
    val confirmedCount: Int = 0,
    val reviewCount: Int = 0,
    val noMatchCount: Int = 0,
    val unavailableCount: Int = 0
)

@Serializable
data class StyleFingerprint(
    val sampleCount: Int = 0,
    val wordCount: Int = 0,
    val averageWordLength: Double = 0.0,
    val averageSentenceWords: Double = 0.0,
    val vocabularyRichness: Double = 0.0,
    val punctuationPer100Words: Double = 0.0,
    val questionPer100Words: Double = 0.0,
    val exclamationPer100Words: Double = 0.0,
    val uppercaseWordRatio: Double = 0.0
)

@Serializable
data class StyleComparison(
    val sourceA: String,
    val sourceB: String,
    val similarity: Double,
    val sampleCountA: Int,
    val sampleCountB: Int,
    val note: String = "Supporting behavioral similarity only; never identity proof."
)

@Serializable
data class TimezoneHypothesis(
    val utcOffsetHours: Int,
    val score: Double,
    val note: String = "Low-confidence activity-window hypothesis; not location evidence."
)

@Serializable
data class BehavioralProfile(
    val textSampleCount: Int = 0,
    val timestampedSampleCount: Int = 0,
    val hourlyActivityUtc: List<Int> = List(24) { 0 },
    val dominantPostingWindowUtc: String? = null,
    val timezoneHypotheses: List<TimezoneHypothesis> = emptyList(),
    val topics: List<String> = emptyList(),
    val overallStyle: StyleFingerprint = StyleFingerprint(),
    val crossSourceStyle: List<StyleComparison> = emptyList(),
    val caveat: String = "Behavioral analysis is descriptive/supporting only and is excluded from confirmed identity attribution."
)

@Serializable
data class InteractionEdge(
    val source: String,
    val target: String,
    val mentions: Int = 0,
    val replies: Int = 0,
    val otherInteractions: Int = 0,
    val weight: Double = 0.0
)

@Serializable
data class InfluenceNode(
    val node: String,
    val weightedDegree: Double,
    val pageRank: Double
)

@Serializable
data class InteractionCluster(
    val id: Int,
    val nodes: List<String>,
    val totalEdgeWeight: Double
)

@Serializable
data class InteractionGraphSummary(
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val totalInteractionWeight: Double = 0.0,
    val edges: List<InteractionEdge> = emptyList(),
    val influenceNodes: List<InfluenceNode> = emptyList(),
    val clusters: List<InteractionCluster> = emptyList(),
    val caveat: String = "Network metrics describe the collected public interaction sample; missing/private/API-inaccessible activity can materially change the graph."
)

@Serializable
data class OsintAnalysisBundle(
    val identitySurface: IdentitySurfaceMap = IdentitySurfaceMap(),
    val behavioral: BehavioralProfile = BehavioralProfile(),
    val interactionGraph: InteractionGraphSummary = InteractionGraphSummary()
)

/**
 * Deterministic, local post-processing over evidence already collected by the
 * authorized scan. No additional network requests are made here.
 */
object OsintPostProcessor {
    fun analyze(
        input: IdentityInput,
        profiles: List<ProfileScanResult>,
        evidence: EvidenceCollection
    ): OsintAnalysisBundle = OsintAnalysisBundle(
        identitySurface = buildSurfaceMap(profiles),
        behavioral = buildBehavioralProfile(evidence.evidence),
        interactionGraph = buildInteractionGraph(input, evidence.relationships)
    )

    fun buildSurfaceMap(profiles: List<ProfileScanResult>): IdentitySurfaceMap {
        val entries = profiles
            .distinctBy { it.candidate.url.lowercase() }
            .map { result ->
                val unavailable = !result.exists && (
                    result.verificationStatus?.contains("unverifiable", true) == true ||
                        result.verificationStatus?.contains("challenge", true) == true ||
                        result.verificationStatus?.contains("authentication", true) == true ||
                        result.verificationStatus?.contains("unavailable", true) == true
                    )
                val state = when {
                    result.exists && result.verified -> PresenceState.Exists
                    result.exists -> PresenceState.SuspiciousSimilarity
                    unavailable -> PresenceState.Unavailable
                    else -> PresenceState.NoMatch
                }
                SurfacePresence(
                    platform = result.candidate.platform.name,
                    username = result.candidate.username,
                    url = result.candidate.url,
                    state = state,
                    confidence = result.candidate.confidence.toDouble().coerceIn(0.0, 1.0),
                    reason = result.verificationStatus
                        ?: result.provenance
                        ?: when (state) {
                            PresenceState.Exists -> "Directly verified public profile"
                            PresenceState.SuspiciousSimilarity -> "Public account exists but attribution is not independently verified"
                            PresenceState.NoMatch -> "No matching public account observed in this scan"
                            PresenceState.Unavailable -> "Provider could not be conclusively checked"
                        }
                )
            }
            .sortedWith(compareBy<SurfacePresence> { it.state.ordinal }.thenBy { it.platform })
        return IdentitySurfaceMap(
            entries = entries,
            confirmedCount = entries.count { it.state == PresenceState.Exists },
            reviewCount = entries.count { it.state == PresenceState.SuspiciousSimilarity },
            noMatchCount = entries.count { it.state == PresenceState.NoMatch },
            unavailableCount = entries.count { it.state == PresenceState.Unavailable }
        )
    }

    fun buildBehavioralProfile(evidence: List<Evidence>): BehavioralProfile {
        val textEvidence = evidence.filter { !it.snippet.isNullOrBlank() }
        val timestamped = textEvidence.filter { it.observedAtEpochMillis != null }
        val hours = MutableList(24) { 0 }
        timestamped.forEach { item ->
            val hour = Instant.ofEpochMilli(item.observedAtEpochMillis!!)
                .atOffset(ZoneOffset.UTC).hour
            hours[hour]++
        }

        val sourceSamples = textEvidence.groupBy { sourceKey(it) }
            .filterValues { it.size >= MIN_STYLE_SAMPLES_PER_SOURCE }
        val sourceStyles = sourceSamples.mapValues { (_, samples) ->
            styleFingerprint(samples.mapNotNull(Evidence::snippet))
        }
        val comparisons = mutableListOf<StyleComparison>()
        val sourceKeys = sourceStyles.keys.sorted()
        for (i in sourceKeys.indices) {
            for (j in i + 1 until sourceKeys.size) {
                val a = sourceKeys[i]
                val b = sourceKeys[j]
                comparisons += StyleComparison(
                    sourceA = a,
                    sourceB = b,
                    similarity = styleSimilarity(sourceStyles.getValue(a), sourceStyles.getValue(b)),
                    sampleCountA = sourceSamples.getValue(a).size,
                    sampleCountB = sourceSamples.getValue(b).size
                )
            }
        }

        return BehavioralProfile(
            textSampleCount = textEvidence.size,
            timestampedSampleCount = timestamped.size,
            hourlyActivityUtc = hours,
            dominantPostingWindowUtc = dominantWindow(hours),
            timezoneHypotheses = timezoneHypotheses(hours),
            topics = topTopics(textEvidence.mapNotNull(Evidence::snippet)),
            overallStyle = styleFingerprint(textEvidence.mapNotNull(Evidence::snippet)),
            crossSourceStyle = comparisons.sortedByDescending { it.similarity }.take(MAX_STYLE_COMPARISONS)
        )
    }

    fun buildInteractionGraph(
        input: IdentityInput,
        relationships: List<EvidenceRelationship>
    ): InteractionGraphSummary {
        val authorized = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map(::normalizeHandle)
            .filter(String::isNotBlank)
            .toSet()
        if (authorized.isEmpty()) return InteractionGraphSummary()

        data class Counter(var mentions: Int = 0, var replies: Int = 0, var other: Int = 0)
        val counters = linkedMapOf<Pair<String, String>, Counter>()
        relationships.forEach { relationship ->
            val relation = relationship.relation.uppercase()
            val isInteraction = relation.contains("MENTION") || relation.contains("REPL") ||
                relation.contains("QUOTE") || relation.contains("RETWEET") || relation.contains("INTERACT")
            if (!isInteraction) return@forEach
            val from = normalizeHandle(relationship.fromValue)
            val to = normalizeHandle(relationship.toValue)
            if (from.isBlank() || to.isBlank() || from == to) return@forEach
            if (from !in authorized && to !in authorized && !relation.contains("REPL") && !relation.contains("MENTION")) {
                return@forEach
            }
            val counter = counters.getOrPut(from to to) { Counter() }
            when {
                relation.contains("REPL") -> counter.replies++
                relation.contains("MENTION") -> counter.mentions++
                else -> counter.other++
            }
        }

        val edges = counters.map { (pair, count) ->
            InteractionEdge(
                source = pair.first,
                target = pair.second,
                mentions = count.mentions,
                replies = count.replies,
                otherInteractions = count.other,
                weight = count.mentions + count.replies * 2.0 + count.other * 1.25
            )
        }.filter { it.weight > 0.0 }

        val nodes = edges.flatMap { listOf(it.source, it.target) }.distinct().sorted()
        if (nodes.isEmpty()) return InteractionGraphSummary()
        val ranks = pageRank(nodes, edges)
        val weightedDegree = nodes.associateWith { node ->
            edges.filter { it.source == node || it.target == node }.sumOf(InteractionEdge::weight)
        }
        val influence = nodes.map { node ->
            InfluenceNode(node, weightedDegree[node] ?: 0.0, ranks[node] ?: 0.0)
        }.sortedWith(compareByDescending<InfluenceNode> { it.pageRank }.thenByDescending { it.weightedDegree })
            .take(MAX_INFLUENCE_NODES)

        return InteractionGraphSummary(
            nodeCount = nodes.size,
            edgeCount = edges.size,
            totalInteractionWeight = edges.sumOf(InteractionEdge::weight),
            edges = edges.sortedByDescending(InteractionEdge::weight).take(MAX_EXPORTED_EDGES),
            influenceNodes = influence,
            clusters = connectedComponents(nodes, edges)
        )
    }

    private fun sourceKey(evidence: Evidence): String {
        evidence.providerId?.takeIf(String::isNotBlank)?.let { return it }
        val host = evidence.sourceUrl?.let { raw -> runCatching { URI(raw).host }.getOrNull() }
        return host?.removePrefix("www.") ?: "unknown-source"
    }

    private fun dominantWindow(hours: List<Int>): String? {
        if (hours.sum() < MIN_TIMEZONE_SAMPLES) return null
        var bestStart = 0
        var bestScore = -1
        for (start in 0 until 24) {
            val score = (0 until WINDOW_HOURS).sumOf { hours[(start + it) % 24] }
            if (score > bestScore) {
                bestStart = start
                bestScore = score
            }
        }
        val end = (bestStart + WINDOW_HOURS) % 24
        return "%02d:00–%02d:00 UTC".format(bestStart, end)
    }

    private fun timezoneHypotheses(hours: List<Int>): List<TimezoneHypothesis> {
        val total = hours.sum()
        if (total < MIN_TIMEZONE_SAMPLES) return emptyList()
        return (-12..14).map { offset ->
            var awake = 0.0
            var evening = 0.0
            hours.forEachIndexed { utcHour, count ->
                val local = ((utcHour + offset) % 24 + 24) % 24
                if (local in 7..23 || local == 0) awake += count
                if (local in 18..23) evening += count
            }
            val score = ((awake / total) * 0.8 + (evening / total) * 0.2).coerceIn(0.0, 1.0)
            TimezoneHypothesis(offset, score)
        }.sortedByDescending(TimezoneHypothesis::score)
            .take(3)
            .filter { it.score >= 0.55 }
    }

    private fun styleFingerprint(samples: List<String>): StyleFingerprint {
        val clean = samples.map(String::trim).filter(String::isNotBlank)
        if (clean.isEmpty()) return StyleFingerprint()
        val words = clean.flatMap(::words)
        if (words.isEmpty()) return StyleFingerprint(sampleCount = clean.size)
        val sentences = clean.flatMap { text ->
            text.split(Regex("[.!?]+"))
                .map(::words)
                .filter(List<String>::isNotEmpty)
        }
        val punctuation = clean.sumOf { text -> text.count { it in ".,;:!?-—()[]" } }
        val questions = clean.sumOf { it.count { ch -> ch == '?' } }
        val exclamations = clean.sumOf { it.count { ch -> ch == '!' } }
        val uppercaseWords = words.count { word -> word.length >= 2 && word.all(Char::isUpperCase) }
        return StyleFingerprint(
            sampleCount = clean.size,
            wordCount = words.size,
            averageWordLength = words.map(String::length).averageSafe(),
            averageSentenceWords = sentences.map(List<String>::size).averageSafe(),
            vocabularyRichness = words.map(String::lowercase).distinct().size.toDouble() / words.size,
            punctuationPer100Words = punctuation * 100.0 / words.size,
            questionPer100Words = questions * 100.0 / words.size,
            exclamationPer100Words = exclamations * 100.0 / words.size,
            uppercaseWordRatio = uppercaseWords.toDouble() / words.size
        )
    }

    private fun styleSimilarity(a: StyleFingerprint, b: StyleFingerprint): Double {
        if (a.wordCount < MIN_STYLE_WORDS || b.wordCount < MIN_STYLE_WORDS) return 0.0
        val av = styleVector(a)
        val bv = styleVector(b)
        val dot = av.indices.sumOf { av[it] * bv[it] }
        val an = sqrt(av.sumOf { it * it })
        val bn = sqrt(bv.sumOf { it * it })
        if (an == 0.0 || bn == 0.0) return 0.0
        return (dot / (an * bn)).coerceIn(0.0, 1.0)
    }

    private fun styleVector(value: StyleFingerprint): DoubleArray = doubleArrayOf(
        value.averageWordLength / 10.0,
        value.averageSentenceWords / 40.0,
        value.vocabularyRichness,
        value.punctuationPer100Words / 30.0,
        value.questionPer100Words / 10.0,
        value.exclamationPer100Words / 10.0,
        value.uppercaseWordRatio
    )

    private fun topTopics(samples: List<String>): List<String> {
        val counts = linkedMapOf<String, Int>()
        samples.flatMap(::words)
            .map { it.lowercase() }
            .filter { it.length >= 3 && it !in STOP_WORDS && it.none(Char::isDigit) }
            .forEach { token -> counts[token] = (counts[token] ?: 0) + 1 }
        return counts.entries
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TOPICS)
            .map(Map.Entry<String, Int>::key)
    }

    private fun pageRank(nodes: List<String>, edges: List<InteractionEdge>): Map<String, Double> {
        if (nodes.isEmpty()) return emptyMap()
        val n = nodes.size.toDouble()
        var ranks = nodes.associateWith { 1.0 / n }
        val outgoing = nodes.associateWith { node -> edges.filter { it.source == node }.sumOf(InteractionEdge::weight) }
        repeat(PAGERANK_ITERATIONS) {
            val next = nodes.associateWith { (1.0 - PAGERANK_DAMPING) / n }.toMutableMap()
            nodes.forEach { source ->
                val totalOut = outgoing[source] ?: 0.0
                if (totalOut <= 0.0) {
                    val share = PAGERANK_DAMPING * (ranks[source] ?: 0.0) / n
                    nodes.forEach { next[it] = (next[it] ?: 0.0) + share }
                } else {
                    edges.filter { it.source == source }.forEach { edge ->
                        val share = PAGERANK_DAMPING * (ranks[source] ?: 0.0) * edge.weight / totalOut
                        next[edge.target] = (next[edge.target] ?: 0.0) + share
                    }
                }
            }
            ranks = next
        }
        return ranks
    }

    private fun connectedComponents(nodes: List<String>, edges: List<InteractionEdge>): List<InteractionCluster> {
        val adjacency = nodes.associateWith { mutableSetOf<String>() }
        edges.forEach { edge ->
            adjacency[edge.source]?.add(edge.target)
            adjacency[edge.target]?.add(edge.source)
        }
        val seen = mutableSetOf<String>()
        val clusters = mutableListOf<InteractionCluster>()
        nodes.forEach { start ->
            if (!seen.add(start)) return@forEach
            val queue = ArrayDeque<String>()
            queue.add(start)
            val component = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component += node
                adjacency[node].orEmpty().forEach { neighbor ->
                    if (seen.add(neighbor)) queue.add(neighbor)
                }
            }
            val nodeSet = component.toSet()
            val weight = edges.filter { it.source in nodeSet && it.target in nodeSet }.sumOf(InteractionEdge::weight)
            clusters += InteractionCluster(clusters.size + 1, component.sorted(), weight)
        }
        return clusters.sortedByDescending(InteractionCluster::totalEdgeWeight)
    }

    private fun normalizeHandle(value: String): String = value.trim()
        .removePrefix("@")
        .removePrefix("u/")
        .substringBefore('?')
        .substringBefore('/')
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9_.-]{2,64}")) }
        .orEmpty()

    private fun words(text: String): List<String> = WORD.findAll(text).map(MatchResult::value).toList()
    private fun List<Int>.averageSafe(): Double = if (isEmpty()) 0.0 else average()

    private const val MIN_TIMEZONE_SAMPLES = 8
    private const val WINDOW_HOURS = 5
    private const val MIN_STYLE_SAMPLES_PER_SOURCE = 3
    private const val MIN_STYLE_WORDS = 40
    private const val MAX_STYLE_COMPARISONS = 8
    private const val MAX_INFLUENCE_NODES = 8
    private const val MAX_EXPORTED_EDGES = 200
    private const val MAX_TOPICS = 12
    private const val PAGERANK_ITERATIONS = 24
    private const val PAGERANK_DAMPING = 0.85
    private val WORD = Regex("[A-Za-z][A-Za-z'_-]*")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "that", "this", "with", "from", "have", "has", "had", "not", "but", "are", "was", "were", "you", "your", "they", "their", "its", "can", "could", "would", "should", "will", "just", "about", "into", "than", "then", "when", "where", "what", "which", "who", "why", "how", "been", "being", "also", "some", "more", "most", "very", "really", "only", "there", "here", "out", "our", "all", "any"
    )
}

@Serializable
data class SolarPosition(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val approximateShadowBearingDegrees: Double,
    val shadowLengthToObjectHeightRatio: Double?
)

/** Local NOAA-style solar geometry; no network/API is required. */
object GeoTemporalAnalyzer {
    fun solarPosition(latitude: Double, longitude: Double, timestampUtcMillis: Long): SolarPosition {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        val instant = Instant.ofEpochMilli(timestampUtcMillis)
        val epochSeconds = instant.epochSecond.toDouble()
        val julianDay = epochSeconds / 86400.0 + 2440587.5
        val centuries = (julianDay - 2451545.0) / 36525.0

        val geomMeanLong = normalizeDegrees(280.46646 + centuries * (36000.76983 + centuries * 0.0003032))
        val geomMeanAnom = Math.toRadians(357.52911 + centuries * (35999.05029 - 0.0001537 * centuries))
        val ecc = 0.016708634 - centuries * (0.000042037 + 0.0000001267 * centuries)
        val sunEq = sin(geomMeanAnom) * (1.914602 - centuries * (0.004817 + 0.000014 * centuries)) +
            sin(2 * geomMeanAnom) * (0.019993 - 0.000101 * centuries) +
            sin(3 * geomMeanAnom) * 0.000289
        val sunTrueLong = geomMeanLong + sunEq
        val omega = 125.04 - 1934.136 * centuries
        val apparentLong = Math.toRadians(sunTrueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega)))
        val meanObliq = 23.0 + (26.0 + (21.448 - centuries * (46.815 + centuries * (0.00059 - centuries * 0.001813))) / 60.0) / 60.0
        val obliq = Math.toRadians(meanObliq + 0.00256 * cos(Math.toRadians(omega)))
        val declination = kotlin.math.asin(sin(obliq) * sin(apparentLong))

        val y = kotlin.math.tan(obliq / 2.0).let { it * it }
        val l0 = Math.toRadians(geomMeanLong)
        val eqTimeMinutes = 4.0 * Math.toDegrees(
            y * sin(2 * l0) - 2 * ecc * sin(geomMeanAnom) +
                4 * ecc * y * sin(geomMeanAnom) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) -
                1.25 * ecc * ecc * sin(2 * geomMeanAnom)
        )

        val utc = instant.atOffset(ZoneOffset.UTC)
        val minutes = utc.hour * 60.0 + utc.minute + utc.second / 60.0
        val trueSolarMinutes = ((minutes + eqTimeMinutes + 4.0 * longitude) % 1440.0 + 1440.0) % 1440.0
        val hourAngle = Math.toRadians(if (trueSolarMinutes / 4.0 < 0) trueSolarMinutes / 4.0 + 180 else trueSolarMinutes / 4.0 - 180)
        val lat = Math.toRadians(latitude)
        val cosZenith = (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZenith)
        val elevation = 90.0 - Math.toDegrees(zenith)

        val azimuth = normalizeDegrees(
            Math.toDegrees(
                atan2(
                    sin(hourAngle),
                    cos(hourAngle) * sin(lat) - kotlin.math.tan(declination) * cos(lat)
                )
            ) + 180.0
        )
        val shadowBearing = normalizeDegrees(azimuth + 180.0)
        val shadowRatio = if (elevation > 1.0) {
            1.0 / kotlin.math.tan(Math.toRadians(elevation))
        } else null

        return SolarPosition(azimuth, elevation, shadowBearing, shadowRatio)
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
}
