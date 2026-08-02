package io.dossier.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.dossier.app.ui.theme.DossierCardShape
import io.dossier.app.ui.theme.NeuralTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Camera + system photo-picker control with explicit state and cleanup. */
@Composable
fun ImageSourcePicker(
    label: String,
    selectedUri: Uri?,
    onImageSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraImageFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun prepareCameraTarget(): Uri {
        cameraImageFile?.delete()
        val target = createTempImageTarget(context)
        cameraImageFile = target.file
        cameraImageUri = target.uri
        return target.uri
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraImageUri
        if (success && uri != null) {
            message = "Camera image selected"
            onImageSelected(uri)
        } else {
            cameraImageFile?.delete()
            message = "Camera capture cancelled"
        }
        cameraImageUri = null
        cameraImageFile = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(prepareCameraTarget())
        } else {
            message = "Camera permission was not granted. You can still use the system photo picker."
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            message = "Photo selected"
            onImageSelected(uri)
        }
    }

    val isLoaded = selectedUri != null
    val outlineColor = if (isLoaded) NeuralTheme.Cobalt else NeuralTheme.BorderColor

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeuralTheme.CardBackground, DossierCardShape)
                .border(1.dp, outlineColor, DossierCardShape)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        message = null
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            cameraLauncher.launch(prepareCameraTarget())
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                ) {
                    Text("Camera", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        message = null
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                ) {
                    Text("Choose photo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (isLoaded) {
                Spacer(Modifier.width(1.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message ?: "Image selected",
                        color = NeuralTheme.Emerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (onClear != null) {
                        TextButton(onClick = onClear) {
                            Text("Remove", color = NeuralTheme.Crimson, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                message?.let {
                    Text(
                        text = it,
                        color = NeuralTheme.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

private data class CameraTarget(val file: File, val uri: Uri)

private fun createTempImageTarget(context: Context): CameraTarget {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(
        dir,
        "capture_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    )
    return CameraTarget(
        file = file,
        uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    )
}
