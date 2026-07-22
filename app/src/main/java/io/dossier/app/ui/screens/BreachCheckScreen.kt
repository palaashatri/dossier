package io.dossier.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import io.dossier.app.domain.breach.PasswordExposureResult
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.HudLevel
import io.dossier.app.ui.components.HudStatusPill
import io.dossier.app.ui.theme.NeuralTheme
import kotlinx.coroutines.launch

@Composable
fun BreachCheckScreen(onNavigateToBrowser: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "BREACH INTELLIGENCE",
                    color = NeuralTheme.Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                io.dossier.app.ui.components.GeminiSpark(size = 14.dp, glowColor = NeuralTheme.Cyan)
            }
            Text(
                text = "Exposure Check",
                color = NeuralTheme.TextPrimary,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
            Text(
                text = "Passwords are checked by SHA-1 prefix only. The app does not fetch or reveal leaked passwords from dumps.",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
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
                placeholder = "Optional",
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
                    coroutineScope.launch {
                        isChecking = true
                        emailResults = emptyList()
                        passwordResults = emptyList()
                        try {
                            val emailDeferred = launch {
                                emailResults = service.checkEmails(
                                    emails = emails,
                                    hibpApiKey = hibpApiKey.ifBlank { null },
                                    deepResearch = true
                                )
                            }
                            val passwordDeferred = launch {
                                passwordResults = service.checkPasswords(passwords)
                            }
                            emailDeferred.join()
                            passwordDeferred.join()
                        } finally {
                            passwordsRaw = ""
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking && (emailsRaw.isNotBlank() || passwordsRaw.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = NeuralTheme.Cobalt),
                shape = io.dossier.app.ui.theme.DossierButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (isChecking) "Checking..." else "Run Exposure Check",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (isChecking) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = NeuralTheme.Cobalt,
                    trackColor = NeuralTheme.BorderColor
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (emailResults.isNotEmpty()) {
                Text(
                    text = "Email Exposure",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                emailResults.forEach { result ->
                    EmailExposureCard(result, onNavigateToBrowser)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (passwordResults.isNotEmpty()) {
                Text(
                    text = "Password Exposure",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
                )
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
private fun EmailExposureCard(
    result: EmailExposureResult,
    onNavigateToBrowser: (String) -> Unit
) {
    val hasBreaches = result.breaches.isNotEmpty()
    val hasEvidence = result.publicEvidence.isNotEmpty()
    val level = when {
        hasBreaches -> HudLevel.CRIT
        hasEvidence -> HudLevel.WARN
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
                Text(
                    text = result.email,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                result.error?.let {
                    Text(
                        text = it,
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            HudStatusPill(
                text = when {
                    hasBreaches -> "${result.breaches.size} BREACHES"
                    hasEvidence -> "PUBLIC HITS"
                    else -> "CLEAR"
                },
                level = level
            )
        }

        if (result.breaches.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            result.breaches.take(8).forEach { breach ->
                Text(
                    text = "${breach.title} ${breach.breachDate ?: ""}".trim(),
                    color = NeuralTheme.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (breach.dataClasses.isNotEmpty()) {
                    Text(
                        text = breach.dataClasses.take(6).joinToString(", "),
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }
        }

        if (result.publicEvidence.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            result.publicEvidence.take(5).forEach { evidence ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToBrowser(evidence.url) }
                        .padding(vertical = 5.dp)
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
                        text = evidence.source,
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
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
                Text(
                    text = result.label,
                    color = NeuralTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "SHA-1 prefix checked: ${result.sha1Prefix}",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            HudStatusPill(
                text = when {
                    result.error != null -> "ERROR"
                    result.isPwned -> "${result.occurrenceCount} HITS"
                    else -> "NOT FOUND"
                },
                level = level
            )
        }

        result.error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = NeuralTheme.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

private fun parseEmails(raw: String): List<String> =
    raw.split(",", "\n", " ")
        .map { it.trim() }
        .filter { it.contains("@") && it.contains(".") }
        .distinctBy { it.lowercase() }

private fun parsePasswords(raw: String): List<String> =
    raw.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
