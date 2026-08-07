package io.dossier.app.domain.case

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.RiskLevel

/** Pure before/after comparison for encrypted saved cases. */
class CaseComparison {

    data class FindingChange(
        val finding: Finding,
        val change: ChangeKind,
        val riskChanged: Boolean = false
    )

    enum class ChangeKind { ADDED, REMOVED, CHANGED, UNCHANGED }

    enum class RemediationVerificationState {
        NotRechecked,
        StillObserved,
        NotObservedInLatestScan,
        StatusChanged
    }

    data class RemediationVerification(
        val remediationId: String,
        val findingKey: String,
        val beforeStatus: RemediationStatus,
        val afterStatus: RemediationStatus?,
        val state: RemediationVerificationState,
        val explanation: String
    )

    data class CaseDiff(
        val added: List<Finding>,
        val removed: List<Finding>,
        val changed: List<FindingChange>,
        val unchanged: List<Finding>,
        val profilesAdded: Int,
        val profilesRemoved: Int,
        val breachesAdded: Int,
        val breachesRemoved: Int,
        val riskDelta: Int,
        val exposureDelta: Int,
        val remediationVerification: List<RemediationVerification> = emptyList()
    )

    fun compare(before: DossierCase, after: DossierCase): CaseDiff {
        val beforeMap = before.findings.associateBy(::key)
        val afterMap = after.findings.associateBy(::key)

        val added = after.findings.filter { key(it) !in beforeMap }
        val removed = before.findings.filter { key(it) !in afterMap }

        val changed = mutableListOf<FindingChange>()
        val unchanged = mutableListOf<Finding>()
        afterMap.forEach { (findingKey, current) ->
            val previous = beforeMap[findingKey] ?: return@forEach
            if (
                previous.risk != current.risk ||
                previous.confidence != current.confidence ||
                previous.evidenceSnippet != current.evidenceSnippet
            ) {
                changed += FindingChange(
                    finding = current,
                    change = ChangeKind.CHANGED,
                    riskChanged = previous.risk != current.risk
                )
            } else {
                unchanged += current
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
            unchanged = unchanged,
            profilesAdded = (afterProfiles - beforeProfiles).size,
            profilesRemoved = (beforeProfiles - afterProfiles).size,
            breachesAdded = (afterBreaches - beforeBreaches).size,
            breachesRemoved = (beforeBreaches - afterBreaches).size,
            riskDelta = riskDelta,
            exposureDelta = exposureDelta,
            remediationVerification = verifyRemediation(before, after, afterMap.keys)
        )
    }

    private fun verifyRemediation(
        before: DossierCase,
        after: DossierCase,
        latestFindingKeys: Set<String>
    ): List<RemediationVerification> {
        val afterById = after.remediationRecords.associateBy(RemediationRecord::remediationId)
        return before.remediationRecords.map { previous ->
            val current = afterById[previous.remediationId]
            val stillObserved = previous.findingKey in latestFindingKeys
            val state = when {
                current != null && current.status != previous.status -> RemediationVerificationState.StatusChanged
                previous.status == RemediationStatus.Completed && stillObserved -> RemediationVerificationState.StillObserved
                previous.status == RemediationStatus.Completed && !stillObserved -> RemediationVerificationState.NotObservedInLatestScan
                else -> RemediationVerificationState.NotRechecked
            }
            val explanation = when (state) {
                RemediationVerificationState.StatusChanged ->
                    "Remediation status changed from ${previous.status} to ${current?.status}."
                RemediationVerificationState.StillObserved ->
                    "The current authorized scan still observed evidence matching this finding; remediation is not verified."
                RemediationVerificationState.NotObservedInLatestScan ->
                    "The latest authorized scan did not observe this finding. This is not proof of global deletion; indexes, caches or archives may still retain it."
                RemediationVerificationState.NotRechecked ->
                    "No conclusive before/after verification is available for this remediation record."
            }
            RemediationVerification(
                remediationId = previous.remediationId,
                findingKey = previous.findingKey,
                beforeStatus = previous.status,
                afterStatus = current?.status,
                state = state,
                explanation = explanation
            )
        }
    }

    private fun key(finding: Finding): String =
        "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}"

    private fun riskWeight(risk: RiskLevel): Int = when (risk) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }
}
