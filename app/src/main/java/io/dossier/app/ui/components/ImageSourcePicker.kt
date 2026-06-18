package io.dossier.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.dossier.app.ui.theme.NeuralTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable image-source picker with both Camera capture and Gallery selection.
 *
 * Shows a row of two options. Camera capture requires the CAMERA permission
 * (requested on first use) and saves to a temp file via FileProvider. Gallery
 * uses the standard GetContent picker. Either way, the chosen [Uri] is returned
 * via [onImageSelected].
 *
 * Replaces the old gallery-only MediaSelectorRow where camera input is wanted.
 */
@Composable
fun ImageSourcePicker(
    label: String,
    selectedUri: Uri?,
    onImageSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // Camera capture launcher — must be declared before the permission launcher
    // (the permission callback calls launch on it).
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { onImageSelected(it) }
        }
        cameraImageUri = null
    }

    // Permission launcher for camera
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempImageUri(context)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        }
    }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    val isLoaded = selectedUri != null
    val outlineColor = if (isLoaded) NeuralTheme.Cobalt else NeuralTheme.BorderColor

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = NeuralTheme.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeuralTheme.CardBackground, io.dossier.app.ui.theme.DossierCardShape)
                .border(1.dp, outlineColor, io.dossier.app.ui.theme.DossierCardShape)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera option
            SourceOption(
                label = "Camera",
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        val uri = createTempImageUri(context)
                        cameraImageUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.weight(1f)
            )

            // Gallery option
            SourceOption(
                label = "Gallery",
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            )
        }

        if (isLoaded) {
            Text(
                text = selectedUri?.path?.substringAfterLast("/") ?: "Image selected",
                color = NeuralTheme.Cobalt,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SourceOption(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(NeuralTheme.SurfaceDark.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = NeuralTheme.Cobalt,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Creates a temp file for camera capture and returns its content:// URI via
 * FileProvider.
 */
private fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { if (!exists()) mkdirs() }
    val file = File(dir, "capture_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
