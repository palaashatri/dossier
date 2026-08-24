package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderDiagnosticsRuntime
import io.dossier.app.domain.discovery.ProviderHealthAssessment
import io.dossier.app.domain.discovery.ProviderHealthReport
import io.dossier.app.domain.discovery.ProviderHealthStatus
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.domain.username.UsernameVariantGenerator
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal fun formatModeCounts(
    profileCount: Int,
    executableUsernameRuleCount: Int?,
    mode: ScanMode
): String {
    return if (executableUsernameRuleCount != null) {
        val wmnCount = minOf(executableUsernameRuleCount, mode.providerLimit)
        "$profileCount profiles • $wmnCount username rules"
    } else {
        "$profileCount profiles"
    }
}

@Composable
fun UsernameDiscoveryScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val input = ScanSession.tempInput
    val primaryUsername = input?.primaryUsername ?: ""
    val fullName = input?.fullName ?: ""
    val originalUsernames = remember(input) {
        input?.usernames.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
    }
    val emails = input?.emails.orEmpty()
    val selectedScanMode by DiscoveryScanPreferences.selectedMode.collectAsState()
    val legacyDeepResearch by ScanSession.deepResearchEnabled.collectAsState()

    val context = LocalContext.current
    val initialWmnState = remember { WhatsMyNameCatalog.state }
    var wmnState by remember { mutableStateOf(initialWmnState) }
    var wmnLoadComplete by remember {
        mutableStateOf(initialWmnState is WhatsMyNameCatalogState.Ready)
    }
    var providerHealthReport by remember { mutableStateOf<ProviderHealthReport?>(null) }

    // Migrate the pre-v2 Deep Research choice into the new authoritative scan
    // depth without silently losing the user's earlier intent.
    LaunchedEffect(Unit) {
        if (legacyDeepResearch && DiscoveryScanPreferences.selectedMode.value == ScanMode.Standard) {
            DiscoveryScanPreferences.setMode(ScanMode.Deep)
        }
        val installedState = withContext(Dispatchers.IO) {
            WhatsMyNameCatalog.install(context.applicationContext)
            WhatsMyNameCatalog.state
        }
        wmnState = installedState
        wmnLoadComplete = true
        providerHealthReport = withContext(Dispatchers.IO) {
            ProviderDiagnosticsRuntime.install(context.applicationContext)
            ProviderDiagnosticsRuntime.report(
                knownProviderIds = ProviderCatalogV2.definitions.map { it.id },
                now = Instant.now()
            )
        }
    }

    val generator = remember { UsernameVariantGenerator() }
    val initialVariants = remember(primaryUsername, fullName, originalUsernames, emails) {
        generator.generateAllSeeds(
            primary = primaryUsername.takeIf { it.isNotBlank() },
            name = fullName.takeIf { it.isNotBlank() },
            usernames = originalUsernames,
            emails = emails
        ).map { it.username }.distinct()
    }

    var variantStates by remember {
        mutableStateOf(initialVariants.associateWith { true })
    }
    var newCustomUsername by remember { mutableStateOf("") }

    val buttonGradient = Brush.horizontalGradient(
        colors = listOf(NeuralTheme.Cobalt, NeuralTheme.Violet)
    )
    val cardShape = io.dossier.app.ui.theme.DossierCardShape

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "REUSE ANALYSIS",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Username Discovery",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                Text(
                    text = "Choose username variants and the public-source budget for this authorized audit.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(20.dp))

                if (primaryUsername.isBlank() && initialVariants.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeuralTheme.Amber, cardShape),
                        shape = cardShape
                    ) {
                        Text(
                            text = "No primary username supplied. Enter a custom variant below if you want username-based provider checks.",
                            color = NeuralTheme.Amber,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                } else {
                    Text(
                        text = if (primaryUsername.isNotBlank()) {
                            "Discovered variants (primary: @$primaryUsername)"
                        } else {
                            "Discovered variants (name-derived)"
                        },
                        color = NeuralTheme.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NeuralTheme.BorderColor, cardShape),
                        shape = cardShape
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            variantStates.forEach { (username, isChecked) ->
                                val itemBorder = if (isChecked) NeuralTheme.Cyan else NeuralTheme.BorderColor
                                val itemBg = if (isChecked) {
                                    NeuralTheme.Cobalt.copy(alpha = 0.12f)
                                } else {
                                    NeuralTheme.CardBackground
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .background(itemBg, RoundedCornerShape(8.dp))
                                        .border(1.dp, itemBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            variantStates = variantStates.toMutableMap().apply {
                                                put(username, !isChecked)
                                            }
                                        }
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = username,
                                        color = if (isChecked) {
                                            NeuralTheme.TextPrimary
                                        } else {
                                            NeuralTheme.TextSecondary
                                        },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            variantStates = variantStates.toMutableMap().apply {
                                                put(username, checked)
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = NeuralTheme.Cyan,
                                            uncheckedColor = NeuralTheme.BorderColor,
                                            checkmarkColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Add custom variant",
                    color = NeuralTheme.Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CyberTextField(
                            value = newCustomUsername,
                            onValueChange = { newCustomUsername = it },
                            label = "Custom Username"
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val normalized = newCustomUsername.trim()
                            if (normalized.isNotBlank() && !variantStates.containsKey(normalized)) {
                                variantStates = variantStates.toMutableMap().apply {
                                    put(normalized, true)
                                }
                                newCustomUsername = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeuralTheme.CardBackground,
                            contentColor = NeuralTheme.Cyan
                        ),
                        shape = io.dossier.app.ui.theme.DossierButtonShape,
                        modifier = Modifier
                            .height(56.dp)
                            .border(
                                1.dp,
                                NeuralTheme.BorderColor,
                                io.dossier.app.ui.theme.DossierButtonShape
                            )
                    ) {
                        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "Scan depth",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Counts separate direct-profile fan-out from the pinned HTTPS username catalog. Search, breach, image and archive operations are reported separately.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
                )

                val currentWmnState = wmnState
                if (wmnLoadComplete && currentWmnState is WhatsMyNameCatalogState.Unavailable) {
                    Text(
                        text = "Username rules unavailable: ${currentWmnState.reason}",
                        color = NeuralTheme.Amber,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                ScanMode.entries.forEach { mode ->
                    ScanModeOption(
                        mode = mode,
                        selected = selectedScanMode == mode,
                        executableUsernameRuleCount =
                            (currentWmnState as? WhatsMyNameCatalogState.Ready)?.executableCount,
                        onSelect = {
                            DiscoveryScanPreferences.setMode(mode)
                            // The v2 mode is authoritative once the user touches
                            // it; keep the legacy flag synchronized for the
                            // existing scanner until ScanCoordinator replaces it.
                            ScanSession.setDeepResearch(mode.includeExtendedDiscovery)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (selectedScanMode == ScanMode.Exhaustive) {
                    Text(
                        text = "Exhaustive enables every currently compatible public profile provider in the reviewed catalog and the extended discovery path. Prefer Wi-Fi and external power for longer mobile scans. You can cancel at any time.",
                        color = NeuralTheme.Amber,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                }

                providerHealthReport?.let { report ->
                    Spacer(modifier = Modifier.height(18.dp))
                    ProviderHealthDiagnosticsPanel(report)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = NeuralTheme.TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .border(
                            1.dp,
                            NeuralTheme.BorderColor,
                            io.dossier.app.ui.theme.DossierButtonShape
                        ),
                    shape = io.dossier.app.ui.theme.DossierButtonShape
                ) {
                    Text("Back", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        val selected = variantStates.filter { it.value }.keys
                        val preservedOriginals = originalUsernames.filter { username ->
                            val state = variantStates.entries.firstOrNull {
                                it.key.equals(username, ignoreCase = true)
                            }
                            state == null || state.value
                        }
                        val mergedUsernames = (selected + preservedOriginals)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase() }
                            .toList()

                        ScanSession.tempInput = input?.copy(usernames = mergedUsernames)
                        onNext()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(buttonGradient, io.dossier.app.ui.theme.DossierButtonShape)
                        .border(
                            1.dp,
                            Color.White.copy(alpha = 0.2f),
                            io.dossier.app.ui.theme.DossierButtonShape
                        ),
                    shape = io.dossier.app.ui.theme.DossierButtonShape,
                    contentPadding = PaddingValues()
                ) {
                    Text("Next Stage", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

internal fun providerHealthSummary(report: ProviderHealthReport): String = listOf(
    "Healthy ${report.healthyCount}",
    "Degraded ${report.degradedCount}",
    "Unavailable ${report.unavailableCount}",
    "Stale ${report.staleCount}",
    "Unvalidated ${report.unvalidatedCount}"
).joinToString(" · ")

private fun providerHealthStatusLabel(status: ProviderHealthStatus): String = when (status) {
    ProviderHealthStatus.Healthy -> "HEALTHY"
    ProviderHealthStatus.Degraded -> "DEGRADED"
    ProviderHealthStatus.Unavailable -> "UNAVAILABLE"
    ProviderHealthStatus.Stale -> "STALE"
    ProviderHealthStatus.Unvalidated -> "UNVALIDATED"
}

@Composable
internal fun ProviderHealthDiagnosticsPanel(report: ProviderHealthReport) {
    val notable = remember(report) {
        report.assessments
            .filter { it.status != ProviderHealthStatus.Healthy }
            .take(MAX_HEALTH_PREVIEW_ROWS)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape),
        shape = io.dossier.app.ui.theme.DossierCardShape
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Provider catalog health",
                color = NeuralTheme.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${report.knownProviderCount} catalog definition(s) · ${report.observedProviderCount} with recorded validation (${(report.coverageRate * 100).toInt()}% coverage)",
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                text = providerHealthSummary(report),
                color = NeuralTheme.Cobalt,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
            Text(
                text = "Health is persisted aggregate provider diagnostics only; catalog membership, an HTTP 200, or a search hit is not live validation or identity evidence.",
                color = NeuralTheme.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 7.dp, bottom = 5.dp)
            )
            if (notable.isEmpty()) {
                Text(
                    text = "No degraded, unavailable, stale, or unvalidated catalog entries are currently recorded.",
                    color = NeuralTheme.Emerald,
                    fontSize = 10.5.sp
                )
            } else {
                notable.forEach { assessment ->
                    ProviderHealthAssessmentRow(assessment)
                }
                val remaining = report.assessments.count { it.status != ProviderHealthStatus.Healthy } - notable.size
                if (remaining > 0) {
                    Text(
                        text = "$remaining additional non-healthy provider(s) omitted by the bounded preview.",
                        color = NeuralTheme.TextMuted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderHealthAssessmentRow(assessment: ProviderHealthAssessment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assessment.providerId,
                color = NeuralTheme.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${assessment.attempts} attempt(s) · ${(assessment.usableResponseRate * 100).toInt()}% usable responses",
                color = NeuralTheme.TextMuted,
                fontSize = 9.5.sp
            )
        }
        Text(
            text = providerHealthStatusLabel(assessment.status),
            color = when (assessment.status) {
                ProviderHealthStatus.Healthy -> NeuralTheme.Emerald
                ProviderHealthStatus.Degraded,
                ProviderHealthStatus.Stale,
                ProviderHealthStatus.Unvalidated -> NeuralTheme.Amber
                ProviderHealthStatus.Unavailable -> NeuralTheme.Crimson
            },
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ScanModeOption(
    mode: ScanMode,
    selected: Boolean,
    executableUsernameRuleCount: Int?,
    onSelect: () -> Unit
) {
    val profileProviderCount = remember(mode) {
        ProviderCatalogV2.legacyProfileDefinitions(mode).size
    }
    val countText = remember(profileProviderCount, executableUsernameRuleCount, mode) {
        formatModeCounts(profileProviderCount, executableUsernameRuleCount, mode)
    }
    val border = if (selected) NeuralTheme.Cobalt else NeuralTheme.BorderColor
    val background = if (selected) {
        NeuralTheme.Cobalt.copy(alpha = 0.10f)
    } else {
        NeuralTheme.CardBackground.copy(alpha = 0.85f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, border, io.dossier.app.ui.theme.DossierCardShape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = NeuralTheme.Cobalt)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.name,
                color = NeuralTheme.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = countText,
                color = if (selected) NeuralTheme.Cobalt else NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = scanModeDescription(mode),
                color = NeuralTheme.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun scanModeDescription(mode: ScanMode): String = when (mode) {
    ScanMode.Quick -> "Highest-priority direct profile providers for a shorter mobile audit."
    ScanMode.Standard -> "Balanced direct-profile coverage; recommended default."
    ScanMode.Deep -> "Broader direct-profile fan-out plus bounded extended and historical discovery."
    ScanMode.Exhaustive -> "All compatible enabled profile definitions plus bounded extended discovery."
}

private const val MAX_HEALTH_PREVIEW_ROWS = 8
