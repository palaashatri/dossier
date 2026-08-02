package io.dossier.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun BreachCheckScreen(onNavigateToBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { BreachCheckService(context) }

    var emailsRaw by rememberSaveable { mutableStateOf("") }
    var passwordsRaw by rememberSaveable { mutableStateOf("") }
    // API keys intentionally do not survive recreation or process restoration.
    var hibpApiKey by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var emailResults by remember { mutableStateOf<List<EmailExposureResult>>(emptyList()) }
    var passwordResults by remember { mutableStateOf<List<PasswordExposureResult>>(emptyList()) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Breach and exposure check",
                color = NeuralTheme.TextPrimary,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Authoritative HIBP results and ordinary public-web mentions are shown separately. Supported email lookups send a six-character SHA-1 prefix; password checks send a five-character prefix. Full passwords never leave this device.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor)
            Spacer(modifier = Modifier.height(18.dp))

            BreachInputField(
                value = emailsRaw,
                onValueChange = {
                    emailsRaw = it
                    validationMessage = null
                },
                label = "Email addresses",
                placeholder = "One per line, or separated by commas",
                minLines = 2,
                keyboardType = KeyboardType.Email
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreachInputField(
                value = hibpApiKey,
                onValueChange = { hibpApiKey = it },
                label = "HIBP API key",
                placeholder = "Optional; required for authoritative email coverage",
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                supportingText = "Used for this screen only and not saved by this form."
            )
            Spacer(modifier = Modifier.height(12.dp))
            BreachInputField(
                value = passwordsRaw,
                onValueChange = {
                    passwordsRaw = it
                    validationMessage = null
                },
                label = "Passwords",
                placeholder = "One exact password per line",
                minLines = 3,
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                supportingText = "Leading and trailing spaces are preserved because they may be part of a password."
            )

            validationMessage?.let {
                Text(
                    text = it,
                    color = NeuralTheme.Crimson,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isChecking) {
                Button(
                    onClick = {
                        val parsedEmails = parseEmailInput(emailsRaw)
                        val passwords = parsePasswordsExactly(passwordsRaw)
                        if (parsedEmails.invalid.isNotEmpty()) {
                            validationMessage = buildString {
                                append("Check invalid email input: ")
                                append(parsedEmails.invalid.take(3).joinToString(", "))
                                if (parsedEmails.invalid.size > 3) append(" and ${parsedEmails.invalid.size - 3} more")
                            }
                            return@Button
                        }
                        if (parsedEmails.valid.isEmpty() && passwords.isEmpty()) {
                            validationMessage = "Enter at least one valid email address or one password."
                            return@Button
                        }

                        // Remove plaintext passwords from Compose state before any
                        // network work begins. The local list remains only for this job.
                        passwordsRaw = ""
                        validationMessage = null
                        emailResults = emptyList()
                        passwordResults = emptyList()
                        checkJob = scope.launch {
                            isChecking = true
                            try {
                                val emailJob = async {
                                    service.checkEmails(
                                        emails = parsedEmails.valid,
                                        hibpApiKey = hibpApiKey.ifBlank { null },
                                        deepResearch = true
                                    )
                                }
                                val passwordJob = async { service.checkPasswords(passwords) }
                                emailResults = emailJob.await()
                                passwordResults = passwordJob.await()
                            } catch (cancelled: CancellationException) {
                                validationMessage = "Check cancelled. Partial results, if any, are retained."
                                throw cancelled
                            } catch (error: Exception) {
                                validationMessage = error.localizedMessage
                                    ?: "The exposure check could not complete."
                            } finally {
                                isChecking = false
                                checkJob = null
                            }
                        }
                    },
                    enabled = emailsRaw.isNotBlank() || passwordsRaw.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                    shape = DossierButtonShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Run check", fontWeight = FontWeight.SemiBold)
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = NeuralTheme.Cobalt,
                    trackColor = NeuralTheme.BorderColor
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { checkJob?.cancel() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Crimson)
                ) {
                    Text("Cancel check", fontWeight = FontWeight.SemiBold)
                }
            }

            if (emailResults.isNotEmpty()) {
                SectionTitle("Email results")
                emailResults.forEach { result ->
                    EmailExposureCard(result, onNavigateToBrowser)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (passwordResults.isNotEmpty()) {
                SectionTitle("Password results")
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { text ->
            { Text(text, fontSize = 11.sp, lineHeight = 15.sp) }
        },
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeuralTheme.Cobalt,
            unfocusedBorderColor = NeuralTheme.BorderColor,
            focusedTextColor = NeuralTheme.TextPrimary,
            unfocusedTextColor = NeuralTheme.TextPrimary,
            focusedLabelColor = NeuralTheme.Cobalt,
            unfocusedLabelColor = NeuralTheme.TextSecondary,
            cursorColor = NeuralTheme.Cobalt,
            focusedContainerColor = NeuralTheme.CardBackground,
            unfocusedContainerColor = NeuralTheme.CardBackground
        ),
        shape = RoundedCornerShape(12.dp),
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
            .background(NeuralTheme.CardBackground, io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.email,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = coverageDescription(result.hibpCoverage),
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HudStatusPill(text = status, level = level)
        }

        result.error?.let {
            Text(
                it,
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (hasBreaches) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Authoritative HIBP records",
                color = NeuralTheme.Crimson,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            result.breaches.take(8).forEach { breach ->
                Text(
                    text = "${breach.title} ${breach.breachDate ?: ""}".trim(),
                    color = NeuralTheme.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (breach.dataClasses.isNotEmpty()) {
                    Text(
                        breach.dataClasses.take(6).joinToString(", "),
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        if (hasPublicEvidence) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Separate public-web leads",
                color = NeuralTheme.Cobalt,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            result.publicEvidence.take(5).forEach { evidence ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToBrowser(evidence.url) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = evidence.title,
                        color = NeuralTheme.Cobalt,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 2
                    )
                    Text(
                        text = "${evidence.source} · ${(evidence.confidence * 100).toInt()}% review confidence",
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.sp,
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
            .background(NeuralTheme.CardBackground, io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.label,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "SHA-1 prefix checked: ${result.sha1Prefix}",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
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
            Text(
                it,
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NeuralTheme.TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)
    )
}

private data class ParsedEmails(val valid: List<String>, val invalid: List<String>)

private fun parseEmailInput(raw: String): ParsedEmails {
    val tokens = raw.split(',', '\n', '\t', ' ')
        .map(String::trim)
        .filter(String::isNotEmpty)
    val valid = tokens.filter(::looksLikeEmail).distinctBy(String::lowercase)
    val invalid = tokens.filterNot(::looksLikeEmail).distinct()
    return ParsedEmails(valid, invalid)
}

private fun looksLikeEmail(value: String): Boolean {
    val at = value.indexOf('@')
    val dot = value.lastIndexOf('.')
    return at > 0 && dot > at + 1 && dot < value.lastIndex && !value.any(Char::isWhitespace)
}

/** Do not trim: spaces can be intentional password characters. */
internal fun parsePasswordsExactly(raw: String): List<String> = raw
    .split('\n')
    .map { it.removeSuffix("\r") }
    .filter { it.isNotEmpty() }
