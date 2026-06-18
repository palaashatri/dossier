package io.dossier.app.domain.risk

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.RiskLevel

class RiskScorer {
    fun score(findings: List<Finding>): RiskLevel {
        if (findings.isEmpty()) return RiskLevel.Low
        
        var hasCritical = false
        var hasHigh = false
        var hasMedium = false

        for (finding in findings) {
            when (finding.risk) {
                RiskLevel.Critical -> hasCritical = true
                RiskLevel.High -> hasHigh = true
                RiskLevel.Medium -> hasMedium = true
                RiskLevel.Low -> {}
            }
        }

        return when {
            hasCritical -> RiskLevel.Critical
            hasHigh -> RiskLevel.High
            hasMedium -> RiskLevel.Medium
            else -> RiskLevel.Low
        }
    }
}
