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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Identity input — a calm 3-step wizard replacing the prior 10-field dump.
 *
 * Step 1: Who are you auditing? (name + primary username)
 * Step 2: Contact signals (emails, phones, aliases — optional)
 * Step 3: Links & visual (profile URLs, selfie, Deep Research toggle)
 *
 * Progressive disclosure — one focus at a time, generous spacing.
 */
@Composable
fun IdentityScreen(onNext: () -> Unit) {
    var step by remember { mutableStateOf(0) }

    // Wizard state — built progressively, committed to ScanSession on finish.
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var primaryUsername by remember { mutableStateOf("") }
    var emails by remember { mutableStateOf("") }
    var phones by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var locations by remember { mutableStateOf("") }
    var organizations by remember { mutableStateOf("") }
    var usernames by remember { mutableStateOf("") }
    var profileUrls by remember { mutableStateOf("") }
    var selfieUri by remember { mutableStateOf<Uri?>(null) }

    // Step 1 validation: at least one of (username, name, email) must be provided.
    val hasName = firstName.isNotBlank() || lastName.isNotBlank()
    val hasUsername = primaryUsername.isNotBlank()
    val hasEmail = emails.split(",").any { it.trim().isNotBlank() }
    val stepOneValid = hasName || hasUsername || hasEmail

    // No separate launcher needed — ImageSourcePicker handles camera + gallery.

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Text(
                    text = "Identity Signal Input",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Configure parameters for your self-audit. All processing is on-device.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                )

                // Progress dots
                StepIndicator(currentStep = step, totalSteps = 3, modifier = Modifier.padding(bottom = 24.dp))

                // Step content with a calm slide transition
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300)))
                    },
                    label = "wizardStep"
                ) { s ->
                    when (s) {
                        0 -> StepOne(
                            firstName, lastName, primaryUsername, emails,
                            { firstName = it }, { lastName = it },
                            { primaryUsername = it }, { emails = it },
                            stepOneValid
                        )
                        1 -> StepTwo(phones, aliases, locations, organizations,
                            { phones = it }, { aliases = it },
                            { locations = it }, { organizations = it })
                        2 -> StepThree(profileUrls, usernames, selfieUri,
                            { profileUrls = it }, { usernames = it },
                            { selfieUri = it })
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.TextPrimary)
                    ) { Text("Back", fontWeight = FontWeight.Medium) }
                }

                Button(
                    onClick = {
                        if (step < 2) {
                            step++
                        } else {
                            // Finish — commit to ScanSession
                            val combinedName = listOf(firstName.trim(), lastName.trim())
                                .filter { it.isNotBlank() }.joinToString(" ")
                            val parsedAliases = aliases.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedEmails = emails.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedPhones = phones.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedLocations = locations.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedOrgs = organizations.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedUsernames = usernames.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            val parsedUrls = profileUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }

                            ScanSession.tempInput = IdentityInput(
                                fullName = combinedName,
                                aliases = parsedAliases,
                                emails = parsedEmails,
                                phones = parsedPhones,
                                locations = parsedLocations,
                                organizations = parsedOrgs,
                                usernames = parsedUsernames,
                                primaryUsername = primaryUsername.ifBlank { null },
                                profileUrls = parsedUrls,
                                selfieUri = selfieUri?.toString()
                            )
                            onNext()
                        }
                    },
                    enabled = step != 0 || stepOneValid,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeuralTheme.Cobalt,
                        disabledContainerColor = NeuralTheme.BorderColor
                    )
                ) {
                    Text(
                        text = if (step < 2) "Continue" else "Start Scan",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StepOne(
    firstName: String, lastName: String,
    primaryUsername: String, emails: String,
    onFirstName: (String) -> Unit, onLastName: (String) -> Unit,
    onPrimaryUsername: (String) -> Unit, onEmails: (String) -> Unit,
    isValid: Boolean
) {
    Column {
        Text("Who are you auditing?", color = NeuralTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Provide at least one of the following. The more you give, the more we find.",
             color = NeuralTheme.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp,
             modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))

        Text("Name", color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CyberTextField(firstName, onFirstName, "First name", Modifier.weight(1f))
            CyberTextField(lastName, onLastName, "Last name", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        Text("Username", color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        CyberTextField(primaryUsername, onPrimaryUsername, "e.g. janedoe")

        Spacer(Modifier.height(16.dp))

        Text("Email", color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        CyberTextField(emails, onEmails, "e.g. jane.doe@gmail.com")

        if (!isValid) {
            Text(
                text = "Enter at least a name, username, or email to continue.",
                color = NeuralTheme.Crimson,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun StepTwo(
    phones: String, aliases: String, locations: String, organizations: String,
    onPhones: (String) -> Unit, onAliases: (String) -> Unit,
    onLocations: (String) -> Unit, onOrganizations: (String) -> Unit
) {
    Column {
        Text("Additional signals", color = NeuralTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Optional — these help confirm that found profiles belong to you.",
             color = NeuralTheme.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp,
             modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))
        CyberTextField(phones, onPhones, "Phone Numbers (comma-separated)")
        Spacer(Modifier.height(14.dp))
        CyberTextField(aliases, onAliases, "Aliases (comma-separated)")
        Spacer(Modifier.height(14.dp))
        CyberTextField(locations, onLocations, "Locations (comma-separated)")
        Spacer(Modifier.height(14.dp))
        CyberTextField(organizations, onOrganizations, "Organizations (comma-separated)")
    }
}

@Composable
private fun StepThree(
    profileUrls: String, usernames: String, selfieUri: Uri?,
    onProfileUrls: (String) -> Unit, onUsernames: (String) -> Unit,
    onPickSelfie: (Uri) -> Unit
) {
    Column {
        Text("Links & visual", color = NeuralTheme.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Specific URLs to check, plus an optional selfie.",
             color = NeuralTheme.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp,
             modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))
        CyberTextField(profileUrls, onProfileUrls, "Profile URLs (comma-separated)")
        Spacer(Modifier.height(14.dp))
        CyberTextField(usernames, onUsernames, "Other Usernames (comma-separated)")
        Spacer(Modifier.height(18.dp))
        io.dossier.app.ui.components.ImageSourcePicker(
            label = "Consented Selfie (optional)",
            selectedUri = selfieUri,
            onImageSelected = onPickSelfie
        )
        Spacer(Modifier.height(18.dp))
        io.dossier.app.ui.components.DeepResearchToggle()
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until totalSteps) {
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= currentStep) NeuralTheme.Cobalt else NeuralTheme.BorderColor)
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

@Composable
fun CyberTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val labelColor = if (isFocused) NeuralTheme.Cobalt else NeuralTheme.TextSecondary

    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = labelColor, fontSize = 12.sp) },
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = NeuralTheme.CardBackground,
            focusedContainerColor = NeuralTheme.CardBackground,
            focusedTextColor = NeuralTheme.TextPrimary,
            unfocusedTextColor = NeuralTheme.TextPrimary,
            cursorColor = NeuralTheme.Cobalt,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                1.dp,
                if (isFocused) SolidColor(NeuralTheme.Cobalt) else SolidColor(NeuralTheme.BorderColor),
                io.dossier.app.ui.theme.DossierCardShape
            ),
        shape = io.dossier.app.ui.theme.DossierCardShape,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
    )
}

@Composable
fun MediaSelectorRow(label: String, selectedUri: Uri?, onSelect: () -> Unit) {
    val isLoaded = selectedUri != null
    val outlineColor = if (isLoaded) NeuralTheme.Cobalt else NeuralTheme.BorderColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.CardBackground, io.dossier.app.ui.theme.DossierCardShape)
            .border(1.dp, outlineColor, io.dossier.app.ui.theme.DossierCardShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = NeuralTheme.TextSecondary, fontSize = 11.sp)
            Text(
                text = selectedUri?.path?.substringAfterLast("/") ?: "No file selected",
                color = if (isLoaded) NeuralTheme.Cobalt else NeuralTheme.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Button(
            onClick = onSelect,
            colors = ButtonDefaults.buttonColors(
                containerColor = NeuralTheme.CardBackground,
                contentColor = NeuralTheme.Cobalt
            ),
            shape = io.dossier.app.ui.theme.DossierCardShape,
            modifier = Modifier.border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierCardShape),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Select", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
