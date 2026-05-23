package com.homestock.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.homestock.voice.SpeechRecognizerManager

/**
 * Microphone button: requests RECORD_AUDIO, runs the recognizer, and reports
 * the final transcript via [onResult]. [onListening] flips while capturing.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MicButton(
    language: String,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    onListening: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember { SpeechRecognizerManager(context) }
    var listening by remember { mutableStateOf(false) }

    val permission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) {
            listening = true
            onListening(true)
            manager.startListening(
                language = language,
                onResult = { listening = false; onListening(false); onResult(it) },
                onError = { listening = false; onListening(false); onError(it) },
            )
        } else {
            onError("Permission micro requise")
        }
    }

    DisposableEffect(Unit) { onDispose { manager.destroy() } }

    IconButton(
        modifier = modifier,
        onClick = {
            if (!permission.status.isGranted) {
                permission.launchPermissionRequest()
                return@IconButton
            }
            if (listening) {
                manager.stop()
                listening = false
                onListening(false)
            } else {
                listening = true
                onListening(true)
                manager.startListening(
                    language = language,
                    onResult = { listening = false; onListening(false); onResult(it) },
                    onError = { listening = false; onListening(false); onError(it) },
                )
            }
        },
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Recherche vocale",
            tint = if (listening) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary,
        )
    }
}
