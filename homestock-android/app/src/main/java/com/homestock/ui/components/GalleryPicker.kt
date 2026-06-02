package com.homestock.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outlined button that opens the system Photo Picker and hands the picked
 * image back as compressed JPEG bytes via [onBytes]. Uses
 * [ActivityResultContracts.PickVisualMedia], which works without any
 * READ_MEDIA_IMAGES permission on Android 13+ and is backported on older
 * versions through Google Play services.
 *
 * The decoding + EXIF rotation + downscale is done on Dispatchers.IO so
 * large gallery photos don't jank the UI.
 */
@Composable
fun GalleryPickerButton(
    onBytes: (ByteArray) -> Unit,
    text: String = "Galerie",
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) pendingUri = uri }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { compressImageUri(context, uri) }
        pendingUri = null
        if (bytes != null) onBytes(bytes)
    }

    OutlinedButton(
        modifier = modifier,
        enabled = enabled,
        onClick = {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    ) {
        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

/**
 * Convenience composable: a "Caméra" outlined button + a "Galerie"
 * GalleryPickerButton side by side. Used wherever the user previously had
 * a single camera button, so picking from the gallery becomes a one-tap
 * alternative without changing the existing camera path.
 */
@Composable
fun CameraOrGalleryButtons(
    onCamera: () -> Unit,
    onGalleryBytes: (ByteArray) -> Unit,
    cameraLabel: String = "Caméra",
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        OutlinedButton(onClick = onCamera, enabled = enabled) {
            Text(cameraLabel)
        }
        Spacer(Modifier.width(8.dp))
        GalleryPickerButton(onBytes = onGalleryBytes, enabled = enabled)
    }
}
