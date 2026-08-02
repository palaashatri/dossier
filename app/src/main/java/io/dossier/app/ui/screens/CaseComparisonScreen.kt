package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme

/** Explicit local saved-case selection, comparison, and deletion. */
@Composable
fun CaseComparisonScreen() {
    val context = LocalContext.current
    val store = remember { CaseStore(context) }
    var cases by remember { mutableStateOf(emptyList<DossierCase>()) }
    var selectedBefore by remember { mutableStateOf<String?>(null) }
    var selectedAfter by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DossierCase?>(null) }

    fun refreshCases() {
        cases = store.list()
        selectedBefore = selectedBefore?.takeIf { id -> cases.any { it.caseId == id } }
        selectedAfter = selectedAfter?.takeIf { id -> cases.any { it.caseId == id } }
        if (selectedAfter == null) selectedAfter = cases.firstOrNull()?.caseId
        if (selectedBefore == null) {
            selectedBefore = cases.firstOrNull { it.caseId != selectedAfter }?.caseId
        }
    }

    LaunchedEffect(Unit) { refreshCases() }

    val beforeCase = cases.firstOrNull { it.caseId == selectedBefore }
    val afterCase = cases.firstOrNull { it.caseId == selectedAfter }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved case?") },
            text = {
                Text(
                    "${target.label} will be permanently removed from this device. This does not delete the source pages or any exported copies."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.delete(target.caseId)
                        pendingDelete = null
                        refreshCases()
                    }
                ) {
                    Text("Delete", color = NeuralTheme.Crimson, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            containerColor = NeuralTheme.CardBackground
        )
    }

    AnimatedObsidianBackground(showGrid = false)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Saved cases",
            color = NeuralTheme.TextPrimary,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Encrypted on this device. Choose an older and newer scan to compare changes.",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
        )

        if (cases.isEmpty()) {
            EmptyCasesState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Select comparison points",
                        color = NeuralTheme.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                items(cases, key = { it.caseId }) { case ->
                    CaseSelectionCard(
                        case = case,
                        isBefore = selectedBefore == case.caseId,
                        isAfter = selectedAfter == case.caseId,
                        onSelectBefore = {
                            selectedBefore = if (selectedBefore == case.caseId) null else case.caseId
                            if (selectedAfter == case.caseId) selectedAfter = null
                        },
                        onSelectAfter = {
                            selectedAfter = if (selectedAfter == case.caseId) null else case.caseId
                            if (selectedBefore == case.caseId) selectedBefore = null
                        },
                        onDelete = { pendingDelete = case }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        beforeCase != null && afterCase != null -> {
                            val diff = remember(beforeCase.caseId, afterCase.caseId) {
                                CaseComparison().compare(beforeCase, afterCase)
                            }
                            RenderDiff(beforeCase.label, afterCase.label, diff)
                        }
                        afterCase != null -> RenderSingleCase(afterCase)
                        beforeCase != null -> RenderSingleCase(beforeCase)
                        else -> Text(
                            text = "Choose at least one saved case to view its snapshot.",
                            color = NeuralTheme.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyCasesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, DossierButtonShape)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text(
            text = "No saved cases",
            color = NeuralTheme.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Run a scan and choose Save encrypted case on the report. Saving is always explicit.",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun CaseSelectionCard(
    case: DossierCase,
    isBefore: Boolean,
    isAfter: Boolean,
    onSelectBefore: () -> Unit,
    onSelectAfter: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = when {
        isAfter -> NeuralTheme.Cobalt
        isBefore -> NeuralTheme.Amber
        else -> NeuralTheme.BorderColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, borderColor, DossierButtonShape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = case.label,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${case.findings.size} findings · ${case.riskLevel.name.lowercase()} risk" +
                        (case.exposure?.let { " · exposure ${it.overall}" } ?: ""),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete ${case.label}",
                    tint = NeuralTheme.Crimson
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSelectBefore,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isBefore) NeuralTheme.Amber else NeuralTheme.TextSecondary
                )
            ) {
                Text(if (isBefore) "Older ✓" else "Set older", fontSize = 11.5.sp)
            }
            OutlinedButton(
                onClick = onSelectAfter,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isAfter) NeuralTheme.Cobalt else NeuralTheme.TextSecondary
                )
            ) {
                Text(if (isAfter) "Newer ✓" else "Set newer", fontSize = 11.5.sp)
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
            text = "Changes",
            color = NeuralTheme.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$beforeLabel  →  $afterLabel",
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        DeltaRow("Risk score", diff.riskDelta, positiveIsGood = false)
        DeltaRow("Exposure score", diff.exposureDelta, positiveIsGood = false)
        DeltaRow(
            "Discovered profiles",
            diff.profilesAdded - diff.profilesRemoved,
            positiveIsGood = null
        )
        DeltaRow(
            "Known breaches",
            diff.breachesAdded - diff.breachesRemoved,
            positiveIsGood = false
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(8.dp))

        DiffList("Added evidence", diff.added.map { it.value to it.risk }, NeuralTheme.Crimson)
        DiffList("Removed evidence", diff.removed.map { it.value to it.risk }, NeuralTheme.Emerald)
        if (diff.changed.isNotEmpty()) {
            Text(
                text = "Changed evidence (${diff.changed.size})",
                color = NeuralTheme.Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            diff.changed.forEach {
                Text(
                    text = "• ${it.finding.value} (${it.finding.risk.name.lowercase()})",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/** null means direction is informational rather than good/bad. */
@Composable
private fun DeltaRow(label: String, value: Int, positiveIsGood: Boolean?) {
    val color = when {
        value == 0 -> NeuralTheme.TextSecondary
        positiveIsGood == null -> NeuralTheme.Cobalt
        value > 0 && positiveIsGood -> NeuralTheme.Emerald
        value < 0 && !positiveIsGood -> NeuralTheme.Emerald
        else -> NeuralTheme.Crimson
    }
    val sign = if (value > 0) "+" else ""
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = NeuralTheme.TextSecondary, fontSize = 12.5.sp)
        Text(
            text = "$sign$value",
            color = color,
            fontSize = 12.5.sp,
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
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 9.dp, bottom = 4.dp)
    )
    items.forEach { (value, risk) ->
        Text(
            text = "• [${risk.name}] $value",
            color = NeuralTheme.TextPrimary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun RenderSingleCase(case: DossierCase) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DossierButtonShape)
            .background(NeuralTheme.CardBackground)
            .border(1.dp, NeuralTheme.BorderColor, DossierButtonShape)
            .padding(18.dp)
    ) {
        Text(
            text = "Snapshot",
            color = NeuralTheme.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = case.label,
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${case.findings.size} findings · ${case.riskLevel.name.lowercase()} risk",
            color = NeuralTheme.TextPrimary,
            fontSize = 12.5.sp
        )
        case.exposure?.let { exposure ->
            Text(
                text = "Exposure: ${exposure.dimensions.joinToString(", ") { dimension -> "${dimension.dimension.name.lowercase()} ${dimension.score}" }}",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = "Choose another case as the other comparison point to calculate a delta.",
            color = NeuralTheme.TextMuted,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
