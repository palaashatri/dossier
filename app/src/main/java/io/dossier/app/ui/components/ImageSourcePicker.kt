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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
    val coroutineScope = rememberCoroutineScope()
    var cameraImageUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var ownedImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previousSelectedUri by remember { mutableStateOf(selectedUri) }
    val cameraImageUri = cameraImageUriString?.let(Uri::parse)

    fun cleanupCameraTarget() {
        ImageSourceStorage.deleteOwnedFile(
            imagesDirectory = File(context.cacheDir, ImageSourceStorage.IMAGE_DIRECTORY),
            path = cameraImagePath
        )
        cameraImagePath = null
        cameraImageUriString = null
    }

    fun prepareCameraTarget(): Uri? {
        cleanupCameraTarget()
        val target = runCatching { createTempImageTarget(context) }
            .onFailure { message = "Camera image could not be prepared. Try another source." }
            .getOrNull() ?: return null
        cameraImagePath = target.file.absolutePath
        cameraImageUriString = target.uri.toString()
        return target.uri
    }

    fun importImage(source: Uri, cameraPath: String? = null) {
        if (isImporting) return
        isImporting = true
        message = "Preparing photo…"
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ImageSourceStorage.copyToAppStorage(context, source)
                }
                when (result) {
                    is ImageSourceStorage.CopyResult.Success -> {
                        val previousOwnedPath = ownedImagePath
                        ownedImagePath = result.file.absolutePath
                        onImageSelected(result.uri)
                        ImageSourceStorage.deleteOwnedFile(
                            imagesDirectory = ImageSourceStorage.imagesDirectory(context),
                            path = previousOwnedPath
                        )
                        message = "Photo selected"
                    }

                    ImageSourceStorage.CopyResult.TooLarge -> {
                        message = "Photo is too large. Choose an image under 24 MB."
                    }

                    ImageSourceStorage.CopyResult.Unsupported -> {
                        message = "That file is not a supported image."
                    }

                    ImageSourceStorage.CopyResult.Failed -> {
                        message = "Photo could not be read. Choose another image."
                    }
                }
            } finally {
                cameraPath?.let {
                    ImageSourceStorage.deleteOwnedFile(
                        imagesDirectory = File(context.cacheDir, ImageSourceStorage.IMAGE_DIRECTORY),
                        path = it
                    )
                }
                if (cameraPath == cameraImagePath) {
                    cameraImagePath = null
                    cameraImageUriString = null
                }
                isImporting = false
            }
        }
    }

    DisposableEffect(cameraImagePath) {
        val path = cameraImagePath
        onDispose {
            ImageSourceStorage.deleteOwnedFile(
                imagesDirectory = File(context.cacheDir, ImageSourceStorage.IMAGE_DIRECTORY),
                path = path
            )
        }
    }

    LaunchedEffect(selectedUri) {
        val wasSelected = previousSelectedUri != null
        previousSelectedUri = selectedUri
        if (wasSelected && selectedUri == null) {
            ImageSourceStorage.deleteOwnedFile(
                imagesDirectory = ImageSourceStorage.imagesDirectory(context),
                path = ownedImagePath
            )
            ownedImagePath = null
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraImageUri
        val path = cameraImagePath
        if (success && uri != null && path != null) {
            importImage(uri, cameraPath = path)
        } else {
            cleanupCameraTarget()
            message = "Camera capture cancelled"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prepareCameraTarget()?.let(cameraLauncher::launch)
        } else {
            message = "Camera permission was not granted. You can still use the system photo picker."
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            importImage(uri)
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
                        if (isImporting) return@OutlinedButton
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            prepareCameraTarget()?.let(cameraLauncher::launch)
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeuralTheme.Cobalt)
                ) {
                    Text("Camera", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        message = null
                        if (isImporting) return@OutlinedButton
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isImporting,
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
                        TextButton(
                            onClick = {
                                ImageSourceStorage.deleteOwnedFile(
                                    imagesDirectory = ImageSourceStorage.imagesDirectory(context),
                                    path = ownedImagePath
                                )
                                ownedImagePath = null
                                cleanupCameraTarget()
                                message = null
                                onClear()
                            },
                            enabled = !isImporting
                        ) {
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
    val dir = File(context.cacheDir, ImageSourceStorage.IMAGE_DIRECTORY).apply { mkdirs() }
    val file = File.createTempFile("capture_", ".jpg", dir)
    return CameraTarget(
        file = file,
        uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    )
}

/** Local image storage seam shared by the picker and focused JVM tests. */
internal object ImageSourceStorage {
    const val IMAGE_DIRECTORY = "images"
    const val MAX_IMAGE_BYTES = 24L * 1024L * 1024L

    class ImageTooLargeException : IOException("Image exceeds the local size limit")

    sealed interface CopyResult {
        data class Success(val file: File, val uri: Uri) : CopyResult
        data object TooLarge : CopyResult
        data object Unsupported : CopyResult
        data object Failed : CopyResult
    }

    fun imagesDirectory(context: Context): File =
        File(context.applicationContext.filesDir, IMAGE_DIRECTORY)

    fun copyToAppStorage(context: Context, source: Uri): CopyResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val mimeType = runCatching { resolver.getType(source) }.getOrNull()
        if (mimeType != null && !mimeType.startsWith("image/", ignoreCase = true)) {
            return CopyResult.Unsupported
        }

        val declaredLength = runCatching {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (declaredLength > MAX_IMAGE_BYTES) return CopyResult.TooLarge

        val directory = imagesDirectory(appContext)
        if (!directory.exists() && !directory.mkdirs()) return CopyResult.Failed
        val suffix = when {
            mimeType.equals("image/png", ignoreCase = true) -> ".png"
            mimeType.equals("image/webp", ignoreCase = true) -> ".webp"
            mimeType.equals("image/gif", ignoreCase = true) -> ".gif"
            else -> ".jpg"
        }
        val target = runCatching { File.createTempFile("selected_", suffix, directory) }
            .getOrNull() ?: return CopyResult.Failed
        var keepTarget = false
        return try {
            val input = resolver.openInputStream(source) ?: return CopyResult.Failed
            val copied = input.use { stream ->
                FileOutputStream(target).use { output ->
                    copyBounded(stream, output)
                }
            }
            if (copied <= 0L) return CopyResult.Failed
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                target
            )
            keepTarget = true
            CopyResult.Success(target, uri)
        } catch (_: ImageTooLargeException) {
            CopyResult.TooLarge
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CopyResult.Failed
        } finally {
            if (!keepTarget) target.delete()
        }
    }

    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_IMAGE_BYTES
    ): Long {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val remaining = maxBytes - total
            val requested = minOf(buffer.size.toLong(), remaining + 1L).toInt()
            if (requested <= 0) break
            val count = input.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                total += 1L
                if (total > maxBytes) throw ImageTooLargeException()
                output.write(single)
                continue
            }
            total += count
            if (total > maxBytes) throw ImageTooLargeException()
            output.write(buffer, 0, count)
        }
        return total
    }

    fun deleteOwnedFile(imagesDirectory: File, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val root = runCatching { imagesDirectory.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (target == root || !target.path.startsWith(root.path + File.separator)) return false
        return target.delete()
    }
}
