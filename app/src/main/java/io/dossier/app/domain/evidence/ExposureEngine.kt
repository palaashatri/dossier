package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.Serializable

/**
 * Exposure scoring (ROADMAP Milestone 9).
 *
 * The existing [io.dossier.app.domain.risk.RiskScorer] reduces everything to a
 * single worst-case [RiskLevel]. This engine instead breaks exposure down into
 * the six dimensions the roadmap calls for — Identity, Professional, Personal,
 * Contact, Image, Location — each scored 0..100, plus the Top-10 highest-risk
 * findings for quick triage.
 *
 * Each sub-score is the max risk weight of the findings that map to that
 * dimension (a finding may load multiple dimensions). Breach digests load the
 * Contact/Identity dimensions. Scores are explainable: every dimension lists the
 * finding types that contributed.
 */
class ExposureEngine {

    @Serializable
    data class DimensionScore(
        val dimension: ExposureDimension,
        val score: Int,           // 0..100
        val contributingTypes: List<FindingType>
    )

    @Serializable
    data class ExposureResult(
        val dimensions: List<DimensionScore>,
        val overall: Int,         // 0..100
        val topFindings: List<Finding>
    )

    fun score(findings: List<Finding>, breaches: List<BreachDigest> = emptyList()): ExposureResult {
        val byDimension = mutableMapOf<ExposureDimension, MutableList<Finding>>()

        findings.forEach { finding ->
            dimensionOf(finding).forEach { dim ->
                byDimension.getOrPut(dim) { mutableListOf() }.add(finding)
            }
        }

        // Breaches are Contact + Identity exposure even without a typed finding.
        if (breaches.any { it.breachCount > 0 || it.sources.isNotEmpty() }) {
            val proxy = findings.firstOrNull { it.type == FindingType.Email }
                ?: Finding(
                    type = FindingType.Email,
                    value = breaches.first().email,
                    sourceUrl = null,
                    evidenceSnippet = "Breach exposure present",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = ""
                )
            byDimension.getOrPut(ExposureDimension.Contact) { mutableListOf() }.add(proxy)
            byDimension.getOrPut(ExposureDimension.Identity) { mutableListOf() }.add(proxy)
        }

        val dimensionScores = ExposureDimension.values().map { dim ->
            val fs = byDimension[dim].orEmpty()
            val score = fs.maxOfOrNull { riskWeight(it.risk) } ?: 0
            DimensionScore(
                dimension = dim,
                score = score,
                contributingTypes = fs.map { it.type }.distinct()
            )
        }

        val overall = dimensionScores.maxOfOrNull { it.score } ?: 0
        val topFindings = findings.sortedByDescending { riskWeight(it.risk) * 100 + (it.confidence * 100).toInt() }
            .take(10)

        return ExposureResult(
            dimensions = dimensionScores,
            overall = overall,
            topFindings = topFindings
        )
    }

    private fun dimensionOf(finding: Finding): List<ExposureDimension> = when (finding.type) {
        FindingType.Email -> listOf(ExposureDimension.Contact, ExposureDimension.Identity)
        FindingType.Phone -> listOf(ExposureDimension.Contact)
        FindingType.Address, FindingType.Location -> listOf(ExposureDimension.Location, ExposureDimension.Personal)
        FindingType.Username, FindingType.UsernameReuse -> listOf(ExposureDimension.Identity)
        FindingType.Profile, FindingType.PlausibleProfileMatch -> listOf(ExposureDimension.Professional, ExposureDimension.Identity)
        FindingType.Organization -> listOf(ExposureDimension.Professional)
        FindingType.PublicSearchEvidence, FindingType.PublicImageEvidence -> listOf(ExposureDimension.Professional, ExposureDimension.Personal)
        FindingType.ImageConsistency -> listOf(ExposureDimension.Image)
        FindingType.SensitiveSnippet -> listOf(ExposureDimension.Personal, ExposureDimension.Identity)
    }

    private fun riskWeight(risk: RiskLevel): Int = when (risk) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }
}

@Serializable
enum class ExposureDimension {
    Identity,
    Professional,
    Personal,
    Contact,
    Image,
    Location
}
