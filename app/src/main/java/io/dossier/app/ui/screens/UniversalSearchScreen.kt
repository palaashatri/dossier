package io.dossier.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.domain.search.UniversalSeedClassifier
import io.dossier.app.domain.search.UniversalSeedType
import io.dossier.app.ui.components.AnimatedObsidianBackground
import io.dossier.app.ui.components.ImageSourcePicker
import io.dossier.app.ui.theme.DossierButtonShape
import io.dossier.app.ui.theme.NeuralTheme

/** The single launch entry for text and photo-based exposure searches. */
@Composable
fun UniversalSearchScreen(onSearch: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var photoUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var correctedType by rememberSaveable { mutableStateOf<String?>(null) }
    var correctedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val photoUri = photoUriString?.let(Uri::parse)
    val detectedSeed = UniversalSeedClassifier.classify(query, photoUriString)
    val ambiguousText = query.trim().isNotBlank() &&
        query.trim().none(Char::isWhitespace) &&
        detectedSeed?.type in setOf(UniversalSeedType.Name, UniversalSeedType.Username)
    val effectiveSeed = detectedSeed?.let { seed ->
        val override = correctedType
            ?.let { value -> runCatching { UniversalSeedType.valueOf(value) }.getOrNull() }
            ?.takeIf { it == UniversalSeedType.Name || it == UniversalSeedType.Username }
        if (ambiguousText && correctedQuery == query && override != null) {
            correctedTextSeed(seed, query, override)
        } else {
            seed
        }
    }
    val canSearch = effectiveSeed != null

    fun submit() {
        val seed = effectiveSeed ?: return
        val input = seed.toIdentityInput().let { typedInput ->
            // Keep both independently supplied signals when a user adds a photo
            // to a text search; the typed seed still controls text classification.
            if (seed.type != UniversalSeedType.Photo && !photoUriString.isNullOrBlank()) {
                typedInput.copy(selfieUri = photoUriString)
            } else {
                typedInput
            }
        }
        ScanSession.tempInput = input
        onSearch()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedObsidianBackground(showGrid = false)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Dossier",
                color = NeuralTheme.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Search your authorized public exposure from one starting signal.",
                color = NeuralTheme.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Search name, username, phone, email or URL"
                    },
                placeholder = {
                    Text(
                        "Search name, username, phone, email or URL",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeuralTheme.Cobalt,
                    unfocusedBorderColor = NeuralTheme.BorderColor,
                    focusedTextColor = NeuralTheme.TextPrimary,
                    unfocusedTextColor = NeuralTheme.TextPrimary,
                    focusedPlaceholderColor = NeuralTheme.TextSecondary,
                    unfocusedPlaceholderColor = NeuralTheme.TextSecondary,
                    cursorColor = NeuralTheme.Cobalt
                )
            )

            Text(
                text = "Detected: ${detectedSeed?.type?.name ?: "None"}",
                color = if (detectedSeed == null) NeuralTheme.TextSecondary else NeuralTheme.Cobalt,
                fontSize = 12.sp,
                modifier = Modifier.semantics {
                    contentDescription = "Detected seed type: ${detectedSeed?.type?.name ?: "None"}"
                }
            )

            if (ambiguousText) {
                Box {
                    TextButton(
                        onClick = { correctionMenuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Correct detected seed type"
                            stateDescription = "Using: ${effectiveSeed?.type?.name ?: "None"}"
                        }
                    ) {
                        Text(
                            text = "Using: ${effectiveSeed?.type?.name ?: "None"}",
                            color = NeuralTheme.Cobalt,
                            fontSize = 12.sp
                        )
                    }
                    DropdownMenu(
                        expanded = correctionMenuExpanded,
                        onDismissRequest = { correctionMenuExpanded = false }
                    ) {
                        listOf(UniversalSeedType.Name, UniversalSeedType.Username).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    correctedType = type.name
                                    correctedQuery = query
                                    correctionMenuExpanded = false
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            )
                        }
                    }
                }
            }

            ImageSourcePicker(
                label = "Photo seed (optional)",
                selectedUri = photoUri,
                onImageSelected = { photoUriString = it.toString() },
                onClear = { photoUriString = null }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = ::submit,
                enabled = canSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = DossierButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeuralTheme.Cobalt,
                    contentColor = NeuralTheme.OnAccent,
                    disabledContainerColor = NeuralTheme.BorderColor,
                    disabledContentColor = NeuralTheme.TextMuted
                )
            ) {
                Text("SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
        }
    }
}

private fun correctedTextSeed(
    detected: io.dossier.app.domain.search.UniversalSeed,
    raw: String,
    type: UniversalSeedType
): io.dossier.app.domain.search.UniversalSeed = detected.copy(
    type = type,
    raw = raw,
    normalized = when (type) {
        UniversalSeedType.Name -> raw.trim().replace(Regex("\\s+"), " ")
        UniversalSeedType.Username -> raw.trim().removePrefix("@")
        else -> detected.normalized
    }
)
