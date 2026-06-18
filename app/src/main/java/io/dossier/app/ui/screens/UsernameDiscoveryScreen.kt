package io.dossier.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.username.UsernameVariantGenerator
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.theme.NeuralTheme
import io.dossier.app.ui.components.AnimatedObsidianBackground

@Composable
fun UsernameDiscoveryScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val input = ScanSession.tempInput
    val primaryUsername = input?.primaryUsername ?: ""
    
    val generator = remember { UsernameVariantGenerator() }
    val initialVariants = remember(primaryUsername) {
        if (primaryUsername.isNotBlank()) {
            generator.generate(primaryUsername).map { it.username }
        } else {
            emptyList()
        }
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
                text = "Select username variants to passively search across platform registries.",
                color = NeuralTheme.TextSecondary,
                
                fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            if (primaryUsername.isBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.Amber, cardShape),
                    shape = cardShape
                ) {
                    Text(
                        text = "⚠️ No primary username supplied. Enter a custom variant below to compile exposure matches.",
                        color = NeuralTheme.Amber,
                        
                        fontSize = 13.sp,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            } else {
                Text(
                    text = "Discovered Variants (Primary: @$primaryUsername)",
                    color = NeuralTheme.Cyan,
                    
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = NeuralTheme.CardBackground.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeuralTheme.BorderColor, cardShape),
                    shape = cardShape
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        variantStates.forEach { (username, isChecked) ->
                            val itemBorder = if (isChecked) NeuralTheme.Cyan else NeuralTheme.BorderColor
                            val itemBg = if (isChecked) NeuralTheme.Cobalt.copy(alpha = 0.12f) else NeuralTheme.CardBackground
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                                    .background(itemBg, RoundedCornerShape(8.dp))
                                    .border(1.dp, itemBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        variantStates = variantStates.toMutableMap().apply { put(username, !isChecked) }
                                    }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = username,
                                    color = if (isChecked) NeuralTheme.TextPrimary else NeuralTheme.TextSecondary,
                                    
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        variantStates = variantStates.toMutableMap().apply { put(username, checked ?: false) }
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
                text = "Add Custom Variant",
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
                        if (newCustomUsername.isNotBlank() && !variantStates.containsKey(newCustomUsername)) {
                            variantStates = variantStates.toMutableMap().apply { put(newCustomUsername, true) }
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
                        .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierButtonShape)
                ) {
                    Text("+",  fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            }

            // Pinned action buttons at the bottom
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
                        .border(1.dp, NeuralTheme.BorderColor, io.dossier.app.ui.theme.DossierButtonShape),
                    shape = io.dossier.app.ui.theme.DossierButtonShape
                ) {
                    Text("Back",  fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        val selectedUsernames = variantStates.filter { it.value }.keys.toList()
                        ScanSession.tempInput = input?.copy(
                            usernames = selectedUsernames
                        )
                        onNext()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(buttonGradient, io.dossier.app.ui.theme.DossierButtonShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), io.dossier.app.ui.theme.DossierButtonShape),
                    shape = io.dossier.app.ui.theme.DossierButtonShape,
                    contentPadding = PaddingValues()
                ) {
                    Text("Next Stage",  fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
