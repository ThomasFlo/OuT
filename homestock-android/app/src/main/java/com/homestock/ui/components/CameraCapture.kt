package com.homestock.ui.components

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen CameraX preview with a capture button. On capture the photo is
 * written to the app cache, compressed via [compressImageFile] and returned.
 */
@Composable
fun CameraCapture(
    onCaptured: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                bindCamera(ctx, lifecycleOwner, previewView, imageCapture)
                previewView
            },
        )
        FloatingActionButton(
            onClick = {
                val file = File.createTempFile("capture", ".jpg", context.cacheDir)
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    options,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val bytes = compressImageFile(file)
                            file.delete()
                            onCaptured(bytes)
                        }

                        override fun onError(exc: ImageCaptureException) { /* ignore */ }
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            Icon(Icons.Filled.Camera, contentDescription = "Prendre une photo")
        }
    }
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }, ContextCompat.getMainExecutor(context))
}
