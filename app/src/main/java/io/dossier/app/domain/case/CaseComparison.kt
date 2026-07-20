package io.dossier.app.domain.case

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel

/**
 * Diffs two [DossierCase]s (ROADMAP M14 Scan Comparison, and M13 Timeline).
 *
 * Produces an explainable delta: findings added/removed/changed, profile and
 * breach changes, and the overall risk delta. Pure and Android-free so it is
 * unit-testable; the UI only renders the result.
 *
 * Findings are matched on (type + value + sourceUrl) — the same key
 * [io.dossier.app.domain.scanner.ScanSession] uses for de-duplication, so the
 * comparison is consistent with the scan pipeline.
 */
class CaseComparison {

    data class FindingChange(
        val finding: Finding,
        val change: ChangeKind,
        val riskChanged: Boolean = false
    )

    enum class ChangeKind { ADDED, REMOVED, CHANGED }

    data class CaseDiff(
        val added: List<Finding>,
        val removed: List<Finding>,
        val changed: List<FindingChange>,
        val profilesAdded: Int,
        val profilesRemoved: Int,
        val breachesAdded: Int,
        val breachesRemoved: Int,
        val riskDelta: Int,            // new overall risk weight - old
        val exposureDelta: Int         // new exposure.overall - old (0 if absent)
    )

    fun compare(before: DossierCase, after: DossierCase): CaseDiff {
        val beforeMap = before.findings.associateBy { key(it) }
        val afterMap = after.findings.associateBy { key(it) }

        val added = after.findings.filter { key(it) !in beforeMap }
        val removed = before.findings.filter { key(it) !in afterMap }

        val changed = mutableListOf<FindingChange>()
        afterMap.forEach { (k, aft) ->
            val bef = beforeMap[k]
            if (bef != null && (bef.risk != aft.risk || bef.confidence != aft.confidence)) {
                changed.add(FindingChange(aft, ChangeKind.CHANGED, riskChanged = bef.risk != aft.risk))
            }
        }

        val beforeProfiles = before.profileResults.map { it.candidate.url }.toSet()
        val afterProfiles = after.profileResults.map { it.candidate.url }.toSet()
        val beforeBreaches = before.breachDigests.map { it.email }.toSet()
        val afterBreaches = after.breachDigests.map { it.email }.toSet()

        val riskDelta = riskWeight(after.riskLevel) - riskWeight(before.riskLevel)
        val exposureDelta = (after.exposure?.overall ?: 0) - (before.exposure?.overall ?: 0)

        return CaseDiff(
            added = added,
            removed = removed,
            changed = changed,
            profilesAdded = (afterProfiles - beforeProfiles).size,
            profilesRemoved = (beforeProfiles - afterProfiles).size,
            breachesAdded = (afterBreaches - beforeBreaches).size,
            breachesRemoved = (beforeBreaches - afterBreaches).size,
            riskDelta = riskDelta,
            exposureDelta = exposureDelta
        )
    }

    private fun key(f: Finding): String = "${f.type.name}|${f.value}|${f.sourceUrl ?: ""}"

    private fun riskWeight(r: RiskLevel): Int = when (r) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }
}
