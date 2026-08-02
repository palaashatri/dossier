package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.data.breach.BreachCheckService
import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.breach.HibpCoverage
import io.dossier.app.domain.breach.PasswordExposureResult
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.HudLevel
import io.dossier.app.ui.components.HudStatusPill
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun BreachCheckScreen(onNavigateToBrowser: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { BreachCheckService(context) }

    var emailsRaw by remember { mutableStateOf("") }
    var passwordsRaw by remember { mutableStateOf("") }
    var hibpApiKey by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var emailResults by remember { mutableStateOf<List<EmailExposureResult>>(emptyList()) }
    var passwordResults by remember { mutableStateOf<List<PasswordExposureResult>>(emptyList()) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "BREACH INTELLIGENCE",
                color = NeuralTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "Exposure Check",
                color = NeuralTheme.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            Text(
                text = "HIBP breach-database coverage and ordinary public-web exposure are reported separately. Passwords use k-anonymity: only a five-character SHA-1 prefix leaves the device.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor)
            Spacer(modifier = Modifier.height(18.dp))

            BreachInputField(
                value = emailsRaw,
                onValueChange = { emailsRaw = it },
                label = "Emails",
                placeholder = "name@example.com, other@example.com",
                minLines = 2,
                keyboardType = KeyboardType.Email
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreachInputField(
                value = hibpApiKey,
                onValueChange = { hibpApiKey = it },
                label = "HIBP API key",
                placeholder = "Optional; required for authoritative email breach coverage",
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreachInputField(
                value = passwordsRaw,
                onValueChange = { passwordsRaw = it },
                label = "Passwords to check",
                placeholder = "One password per line",
                minLines = 3,
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val emails = parseEmails(emailsRaw)
                    val passwords = parsePasswords(passwordsRaw)
                    scope.launch {
                        isChecking = true
                        emailResults = emptyList()
                        passwordResults = emptyList()
                        try {
                            val emailJob = async {
                                service.checkEmails(
                                    emails = emails,
                                    hibpApiKey = hibpApiKey.ifBlank { null },
                                    deepResearch = true
                                )
                            }
                            val passwordJob = async { service.checkPasswords(passwords) }
                            emailResults = emailJob.await()
                            passwordResults = passwordJob.await()
                        } finally {
                            passwordsRaw = ""
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking && (emailsRaw.isNotBlank() || passwordsRaw.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (isChecking) "Checking…" else "Run Exposure Check", fontWeight = FontWeight.Bold)
            }

            if (isChecking) {
                Spacer(modifier = Modifier.height(18.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = NeuralTheme.Cobalt,
                    trackColor = NeuralTheme.BorderColor
                )
            }

            if (emailResults.isNotEmpty()) {
                SectionTitle("Email breach and public exposure")
                emailResults.forEach { result ->
                    EmailExposureCard(result, onNavigateToBrowser)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (passwordResults.isNotEmpty()) {
                SectionTitle("Password exposure")
                passwordResults.forEach { result ->
                    PasswordExposureCard(result)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun BreachInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    minLines: Int = 1,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeuralTheme.Cobalt,
            unfocusedBorderColor = NeuralTheme.BorderColor,
            focusedTextColor = NeuralTheme.TextPrimary,
            unfocusedTextColor = NeuralTheme.TextPrimary,
            focusedLabelColor = NeuralTheme.Cyan,
            unfocusedLabelColor = NeuralTheme.TextSecondary,
            cursorColor = NeuralTheme.Cobalt,
            focusedContainerColor = NeuralTheme.CardBackground.copy(alpha = 0.7f),
            unfocusedContainerColor = NeuralTheme.CardBackground.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmailExposureCard(result: EmailExposureResult, onNavigateToBrowser: (String) -> Unit) {
    val hasBreaches = result.breaches.isNotEmpty()
    val hasPublicEvidence = result.publicEvidence.isNotEmpty()
    val level = when {
        hasBreaches -> HudLevel.CRIT
        result.hibpCoverage == HibpCoverage.ConfirmedNoBreaches && !hasPublicEvidence -> HudLevel.OK
        else -> HudLevel.WARN
    }
    val status = when {
        hasBreaches -> "${result.breaches.size} BREACHES"
        result.hibpCoverage == HibpCoverage.ConfirmedNoBreaches && hasPublicEvidence -> "NO HIBP / PUBLIC HITS"
        result.hibpCoverage == HibpCoverage.ConfirmedNoBreaches -> "HIBP: NOT FOUND"
        result.hibpCoverage == HibpCoverage.NotConfigured -> "HIBP NOT RUN"
        result.hibpCoverage == HibpCoverage.CredentialsRejected -> "KEY REJECTED"
        result.hibpCoverage == HibpCoverage.RateLimited -> "RATE LIMITED"
        result.hibpCoverage == HibpCoverage.Unavailable -> "HIBP UNAVAILABLE"
        else -> "REVIEW"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.85f), io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.email, color = NeuralTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    text = coverageDescription(result.hibpCoverage),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HudStatusPill(text = status, level = level)
        }

        result.error?.let {
            Text(it, color = NeuralTheme.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (hasBreaches) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Authoritative HIBP breach records", color = NeuralTheme.Crimson, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            result.breaches.take(8).forEach { breach ->
                Text(
                    text = "${breach.title} ${breach.breachDate ?: ""}".trim(),
                    color = NeuralTheme.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 5.dp)
                )
                if (breach.dataClasses.isNotEmpty()) {
                    Text(breach.dataClasses.take(6).joinToString(", "), color = NeuralTheme.TextSecondary, fontSize = 11.sp)
                }
            }
        }

        if (hasPublicEvidence) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Separate public-web exposure leads", color = NeuralTheme.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            result.publicEvidence.take(5).forEach { evidence ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateToBrowser(evidence.url) }.padding(vertical = 5.dp)
                ) {
                    Text(
                        text = evidence.title,
                        color = NeuralTheme.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 2
                    )
                    Text(
                        text = "${evidence.source} · ${(evidence.confidence * 100).toInt()}% review confidence",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun coverageDescription(coverage: HibpCoverage): String = when (coverage) {
    HibpCoverage.ConfirmedBreaches -> "HIBP returned one or more authoritative breach records."
    HibpCoverage.ConfirmedNoBreaches -> "HIBP completed and returned no breached-account record."
    HibpCoverage.NotConfigured -> "No authoritative email breach lookup was performed."
    HibpCoverage.CredentialsRejected -> "Authoritative lookup failed because the API key was rejected."
    HibpCoverage.RateLimited -> "Authoritative lookup could not complete because HIBP rate-limited the request."
    HibpCoverage.Unavailable -> "Authoritative lookup was unavailable; do not interpret this as clear."
}

@Composable
private fun PasswordExposureCard(result: PasswordExposureResult) {
    val level = when {
        result.error != null -> HudLevel.WARN
        result.isPwned -> HudLevel.CRIT
        else -> HudLevel.OK
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground.copy(alpha = 0.85f), io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.label, color = NeuralTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("SHA-1 prefix checked: ${result.sha1Prefix}", color = NeuralTheme.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            HudStatusPill(
                text = when {
                    result.error != null -> "UNAVAILABLE"
                    result.isPwned -> "${result.occurrenceCount} HITS"
                    else -> "NOT FOUND"
                },
                level = level
            )
        }
        result.error?.let {
            Text(it, color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NeuralTheme.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
    )
}

private fun parseEmails(raw: String): List<String> = raw.split(",", "\n", " ")
    .map { it.trim() }
    .filter { it.contains("@") && it.contains(".") }
    .distinctBy { it.lowercase() }

private fun parsePasswords(raw: String): List<String> = raw.lines()
    .map { it.trim() }
    .filter { it.isNotBlank() }
