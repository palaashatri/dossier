package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.case.CaseComparison
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Saved-case browser + comparison (ROADMAP M13 Timeline / M14 Scan Comparison).
 *
 * Lists locally-saved [DossierCase]s (opt-in saves from the Report screen),
 * lets the user pick two, and renders the explainable diff from
 * [CaseComparison]: findings added/removed/changed, profile/breach deltas, and
 * the overall risk + exposure delta. With a single case it shows that case's
 * snapshot as a simple timeline entry.
 *
 * No network, no cloud — everything is read from the local [CaseStore].
 */
@Composable
fun CaseComparisonScreen() {
    val context = LocalContext.current
    var cases by remember { mutableStateOf(emptyList<DossierCase>()) }
    var selectedBefore by remember { mutableStateOf<String?>(null) }
    var selectedAfter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cases = CaseStore(context).list()
        // Default selection: most-recent two, for an instant comparison.
        if (cases.size >= 2) {
            selectedAfter = cases.first().caseId
            selectedBefore = cases[1].caseId
        } else if (cases.size == 1) {
            selectedAfter = cases.first().caseId
        }
    }

    val beforeCase = cases.firstOrNull { it.caseId == selectedBefore }
    val afterCase = cases.firstOrNull { it.caseId == selectedAfter }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "INVESTIGATION TIMELINE",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            io.dossier.app.ui.components.GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Cyan)
        }
        Text(
            text = "Saved Cases & Delta",
            color = NeuralTheme.TextPrimary,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
        )
        Text(
            text = "Local only · no cloud · compare two scans to see what changed",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (cases.isEmpty()) {
            Text(
                text = "No saved cases yet. Run a scan and tap SAVE CASE on the report to keep a local snapshot.",
                color = NeuralTheme.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            return@Column
        }

        // Case picker
        cases.forEach { c ->
            val isBefore = selectedBefore == c.caseId
            val isAfter = selectedAfter == c.caseId
            val borderColor = when {
                isBefore -> NeuralTheme.Amber
                isAfter -> NeuralTheme.Emerald
                else -> NeuralTheme.BorderColor
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(DossierButtonShape)
                    .background(NeuralTheme.CardBackground)
                    .border(1.dp, borderColor, DossierButtonShape)
                    .clickable {
                        // Tap assigns to the empty / older slot; tap again clears.
                        when {
                            isAfter -> selectedAfter = null
                            isBefore -> selectedBefore = null
                            selectedAfter == null -> selectedAfter = c.caseId
                            selectedBefore == null -> selectedBefore = c.caseId
                            else -> { selectedAfter = c.caseId; selectedBefore = null }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = c.label,
                        color = NeuralTheme.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = buildString {
                            if (isBefore) append("BEFORE  ")
                            if (isAfter) append("AFTER")
                        }.trim(),
                        color = if (isBefore) NeuralTheme.Amber else NeuralTheme.Emerald,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "${c.findings.size} findings · ${c.riskLevel.name} risk" +
                        (c.exposure?.let { " · exposure ${it.overall}" } ?: ""),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
            beforeCase != null && afterCase != null -> {
                val diff = CaseComparison().compare(beforeCase, afterCase)
                RenderDiff(beforeLabel = beforeCase.label, afterLabel = afterCase.label, diff = diff)
            }
            afterCase != null -> {
                RenderSingleCase(afterCase)
            }
        }
    }
}

@Composable
private fun RenderDiff(
    beforeLabel: String,
    afterLabel: String,
    diff: CaseComparison.CaseDiff
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text(
            text = "COMPARISON",
            color = NeuralTheme.TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
        Text(
            text = "$beforeLabel  →  $afterLabel",
            color = NeuralTheme.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        DeltaRow("Risk delta", diff.riskDelta) { it >= 0 }
        DeltaRow("Exposure delta", diff.exposureDelta) { it >= 0 }
        DeltaRow("Profiles", diff.profilesAdded - diff.profilesRemoved, signMatters = true)
        DeltaRow("Breaches", diff.breachesAdded - diff.breachesRemoved, signMatters = true)

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        DiffList("ADDED", diff.added.map { it.value to it.risk }, NeuralTheme.Crimson)
        DiffList("REMOVED", diff.removed.map { it.value to it.risk }, NeuralTheme.Emerald)
        if (diff.changed.isNotEmpty()) {
            Text(
                text = "CHANGED (${diff.changed.size})",
                color = NeuralTheme.Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            diff.changed.forEach {
                Text(
                    text = "• ${it.finding.value}  (${it.finding.risk.name})",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun DeltaRow(label: String, value: Int, signMatters: Boolean = false, positiveIsGood: (Int) -> Boolean = { true }) {
    val color = when {
        value == 0 -> NeuralTheme.TextSecondary
        positiveIsGood(value) -> NeuralTheme.Emerald
        else -> NeuralTheme.Crimson
    }
    val sign = if (value > 0) "+" else ""
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = NeuralTheme.TextSecondary, fontSize = 12.sp)
        Text(
            text = "$sign$value",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DiffList(title: String, items: List<Pair<String, RiskLevel>>, color: Color) {
    if (items.isEmpty()) return
    Text(
        text = "$title (${items.size})",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    items.forEach { (value, risk) ->
        Text(
            text = "• [$risk] $value",
            color = NeuralTheme.TextPrimary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun RenderSingleCase(c: DossierCase) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text(
            text = "SNAPSHOT",
            color = NeuralTheme.TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
        Text(c.label, color = NeuralTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${c.findings.size} findings · risk ${c.riskLevel.name}", color = NeuralTheme.TextSecondary, fontSize = 12.sp)
        c.exposure?.let {
            Text("Exposure: ${it.dimensions.joinToString(", ") { d -> "${d.dimension.name} ${d.score}" }}",
                color = NeuralTheme.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (c.attackPaths.isNotEmpty()) {
            Text("Attack paths: ${c.attackPaths.size}", color = NeuralTheme.Crimson, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            text = "Select a second case above to compare.",
            color = NeuralTheme.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
