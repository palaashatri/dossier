package io.dossier.app.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.DeepResearchToggle
import io.dossier.app.ui.components.ImageSourcePicker
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme

/** Three-step identity setup with saveable state and narrow-screen-safe actions. */
@Composable
fun IdentityScreen(onNext: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(0) }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var primaryUsername by rememberSaveable { mutableStateOf("") }
    var emails by rememberSaveable { mutableStateOf("") }
    var phones by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    var locations by rememberSaveable { mutableStateOf("") }
    var organizations by rememberSaveable { mutableStateOf("") }
    var usernames by rememberSaveable { mutableStateOf("") }
    var profileUrls by rememberSaveable { mutableStateOf("") }
    var selfieUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var resumePoint by remember { mutableStateOf<Pair<IdentityInput, Boolean>?>(null) }

    LaunchedEffect(Unit) { resumePoint = ScanSession.loadResumePoint(context) }

    val emailSignals = parseSignalList(emails)
    val invalidEmails = emailSignals.filterNot(::looksLikeEmail)
    val hasIdentitySignal = firstName.isNotBlank() || lastName.isNotBlank() ||
        primaryUsername.isNotBlank() || emailSignals.isNotEmpty()
    val stepOneValid = hasIdentitySignal && invalidEmails.isEmpty()
    val normalisedUrls = normaliseProfileUrls(profileUrls)
    val invalidUrls = normalisedUrls.filterNot(::looksLikeWebUrl)
    val finalStepValid = invalidUrls.isEmpty()

    fun commitInput() {
        val fullName = listOf(firstName.trim(), lastName.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
        ScanSession.tempInput = IdentityInput(
            fullName = fullName,
            aliases = parseSignalList(aliases),
            emails = emailSignals,
            phones = parseSignalList(phones),
            locations = parseSignalList(locations),
            organizations = parseSignalList(organizations),
            usernames = parseSignalList(usernames),
            primaryUsername = primaryUsername.trim().removePrefix("@").ifBlank { null },
            profileUrls = normalisedUrls,
            selfieUri = selfieUriString
        )
        onNext()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Start a privacy audit",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Provide only signals you are authorized to audit. Additional signals are optional; they may improve attribution, but every result still requires review.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                resumePoint?.let { (input, deepResearch) ->
                    OutlinedButton(
                        onClick = {
                            ScanSession.tempInput = input
                            ScanSession.setDeepResearch(deepResearch)
                            onNext()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                    ) {
                        Text(
                            text = "Resume last local input: ${input.displaySubject()}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                StepIndicator(
                    currentStep = step,
                    totalSteps = 3,
                    modifier = Modifier.padding(bottom = 22.dp)
                )

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(180)) { -it / 5 } + fadeOut(tween(150)))
                    },
                    label = "identityStep"
                ) { currentStep ->
                    when (currentStep) {
                        0 -> StepOne(
                            firstName = firstName,
                            lastName = lastName,
                            primaryUsername = primaryUsername,
                            emails = emails,
                            invalidEmails = invalidEmails,
                            hasIdentitySignal = hasIdentitySignal,
                            onFirstName = { firstName = it },
                            onLastName = { lastName = it },
                            onPrimaryUsername = { primaryUsername = it },
                            onEmails = { emails = it }
                        )
                        1 -> StepTwo(
                            phones = phones,
                            aliases = aliases,
                            locations = locations,
                            organizations = organizations,
                            onPhones = { phones = it },
                            onAliases = { aliases = it },
                            onLocations = { locations = it },
                            onOrganizations = { organizations = it }
                        )
                        else -> StepThree(
                            profileUrls = profileUrls,
                            usernames = usernames,
                            selfieUri = selfieUriString?.let(Uri::parse),
                            invalidUrls = invalidUrls,
                            onProfileUrls = { profileUrls = it },
                            onUsernames = { usernames = it },
                            onPickSelfie = { selfieUriString = it.toString() },
                            onClearSelfie = { selfieUriString = null }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (step == 0) {
                Button(
                    onClick = { step = 1 },
                    enabled = stepOneValid,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = DossierButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeuralTheme.Cobalt,
                        contentColor = NeuralTheme.OnAccent,
                        disabledContainerColor = NeuralTheme.BorderColor,
                        disabledContentColor = NeuralTheme.TextMuted
                    )
                ) {
                    Text("Continue", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = DossierButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextPrimary)
                    ) {
                        Text("Back", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (step < 2) step++ else commitInput()
                        },
                        enabled = step < 2 || finalStepValid,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = DossierButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeuralTheme.Cobalt,
                            contentColor = NeuralTheme.OnAccent,
                            disabledContainerColor = NeuralTheme.BorderColor,
                            disabledContentColor = NeuralTheme.TextMuted
                        )
                    ) {
                        Text(
                            text = if (step < 2) "Continue" else "Review usernames",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepOne(
    firstName: String,
    lastName: String,
    primaryUsername: String,
    emails: String,
    invalidEmails: List<String>,
    hasIdentitySignal: Boolean,
    onFirstName: (String) -> Unit,
    onLastName: (String) -> Unit,
    onPrimaryUsername: (String) -> Unit,
    onEmails: (String) -> Unit
) {
    Column {
        StepTitle("1. Identify the subject", "Enter at least one name, username, or email address.")
        CyberTextField(
            value = firstName,
            onValueChange = onFirstName,
            label = "First name",
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            value = lastName,
            onValueChange = onLastName,
            label = "Last name",
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            value = primaryUsername,
            onValueChange = onPrimaryUsername,
            label = "Primary username",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            ),
            supportingText = "The leading @ is optional."
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            value = emails,
            onValueChange = onEmails,
            label = "Email addresses",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Default
            ),
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            supportingText = "One per line or separated by commas."
        )
        when {
            invalidEmails.isNotEmpty() -> InlineError(
                "Check invalid email input: ${invalidEmails.take(2).joinToString(", ")}"
            )
            !hasIdentitySignal -> InlineError("Enter at least one identity signal to continue.")
        }
    }
}

@Composable
private fun StepTwo(
    phones: String,
    aliases: String,
    locations: String,
    organizations: String,
    onPhones: (String) -> Unit,
    onAliases: (String) -> Unit,
    onLocations: (String) -> Unit,
    onOrganizations: (String) -> Unit
) {
    Column {
        StepTitle(
            "2. Add corroborating signals",
            "Optional signals help distinguish the subject from people with similar names or handles."
        )
        CyberTextField(
            phones,
            onPhones,
            "Phone numbers",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            supportingText = "One per line or separated by commas."
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            aliases,
            onAliases,
            "Aliases",
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            supportingText = "Nicknames, previous names, or public pen names."
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            locations,
            onLocations,
            "Known locations",
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            supportingText = "Cities or regions only when relevant to the audit."
        )
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            organizations,
            onOrganizations,
            "Organizations",
            minLines = 2,
            maxLines = 4,
            singleLine = false,
            supportingText = "Employers, schools, projects, or public affiliations."
        )
    }
}

@Composable
private fun StepThree(
    profileUrls: String,
    usernames: String,
    selfieUri: Uri?,
    invalidUrls: List<String>,
    onProfileUrls: (String) -> Unit,
    onUsernames: (String) -> Unit,
    onPickSelfie: (Uri) -> Unit,
    onClearSelfie: () -> Unit
) {
    Column {
        StepTitle(
            "3. Add direct sources",
            "Specific links improve precision. A selfie is optional and never silently enables strong face correlation."
        )
        CyberTextField(
            profileUrls,
            onProfileUrls,
            "Profile or personal-site URLs",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            minLines = 3,
            maxLines = 6,
            singleLine = false,
            supportingText = "One per line. Missing https:// is added when the value resembles a web address."
        )
        if (invalidUrls.isNotEmpty()) {
            InlineError("Check invalid URL input: ${invalidUrls.take(2).joinToString(", ")}")
        }
        Spacer(modifier = Modifier.height(12.dp))
        CyberTextField(
            usernames,
            onUsernames,
            "Other known usernames",
            minLines = 2,
            maxLines = 5,
            singleLine = false,
            supportingText = "One per line or separated by commas."
        )
        Spacer(modifier = Modifier.height(18.dp))
        ImageSourcePicker(
            label = "Consented reference photo (optional)",
            selectedUri = selfieUri,
            onImageSelected = onPickSelfie,
            onClear = onClearSelfie
        )
        Spacer(modifier = Modifier.height(18.dp))
        DeepResearchToggle()
    }
}

@Composable
private fun StepTitle(title: String, detail: String) {
    Text(
        text = title,
        color = NeuralTheme.TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = detail,
        color = NeuralTheme.TextSecondary,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 3.dp, bottom = 18.dp)
    )
}

@Composable
private fun InlineError(message: String) {
    Text(
        text = message,
        color = NeuralTheme.Crimson,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 10.dp)
    )
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Identity setup step ${currentStep + 1} of $totalSteps"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = (currentStep + 1).toFloat(),
                    range = 1f..totalSteps.toFloat(),
                    steps = totalSteps - 2
                )
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .height(5.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index <= currentStep) NeuralTheme.Cobalt else NeuralTheme.BorderColor
                    )
            )
        }
    }
}

@Composable
fun IntelSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = NeuralTheme.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
    )
}

/** Shared field retained for the username flow and other legacy call sites. */
@Composable
fun CyberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = minLines == 1,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        supportingText = supportingText?.let { text ->
            { Text(text, fontSize = 11.sp, lineHeight = 15.sp) }
        },
        keyboardOptions = keyboardOptions,
        minLines = minLines,
        maxLines = maxLines,
        singleLine = singleLine,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = NeuralTheme.CardBackground,
            unfocusedContainerColor = NeuralTheme.CardBackground,
            focusedTextColor = NeuralTheme.TextPrimary,
            unfocusedTextColor = NeuralTheme.TextPrimary,
            cursorColor = NeuralTheme.Cobalt,
            focusedBorderColor = NeuralTheme.Cobalt,
            unfocusedBorderColor = NeuralTheme.BorderColor,
            focusedLabelColor = NeuralTheme.Cobalt,
            unfocusedLabelColor = NeuralTheme.TextSecondary
        ),
        modifier = modifier.fillMaxWidth(),
        shape = DossierCardShape,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
    )
}

@Composable
fun MediaSelectorRow(label: String, selectedUri: Uri?, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, DossierCardShape)
            .border(
                1.dp,
                if (selectedUri != null) NeuralTheme.Cobalt else NeuralTheme.BorderColor,
                DossierCardShape
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = NeuralTheme.TextSecondary, fontSize = 12.sp)
            Text(
                text = if (selectedUri != null) "File selected" else "No file selected",
                color = if (selectedUri != null) NeuralTheme.Emerald else NeuralTheme.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        OutlinedButton(
            onClick = onSelect,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
        ) {
            Text("Select", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun parseSignalList(raw: String): List<String> = raw
    .split(',', '\n', '\t')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy(String::lowercase)

private fun looksLikeEmail(value: String): Boolean {
    val at = value.indexOf('@')
    val dot = value.lastIndexOf('.')
    return at > 0 && dot > at + 1 && dot < value.lastIndex && !value.any(Char::isWhitespace)
}

private fun normaliseProfileUrls(raw: String): List<String> = parseSignalList(raw).map { value ->
    when {
        value.startsWith("https://", true) || value.startsWith("http://", true) -> value
        value.contains('.') && !value.contains(' ') -> "https://$value"
        else -> value
    }
}

private fun looksLikeWebUrl(value: String): Boolean = runCatching {
    val parsed = Uri.parse(value)
    (parsed.scheme.equals("https", true) || parsed.scheme.equals("http", true)) &&
        !parsed.host.isNullOrBlank()
}.getOrDefault(false)

private fun IdentityInput.displaySubject(): String = fullName.trim()
    .ifBlank { primaryUsername?.let { "@$it" }.orEmpty() }
    .ifBlank { emails.firstOrNull().orEmpty() }
    .ifBlank { "unnamed subject" }
