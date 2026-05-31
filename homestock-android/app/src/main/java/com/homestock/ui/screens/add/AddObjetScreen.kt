package com.homestock.ui.screens.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.homestock.ui.components.CameraCapture
import com.homestock.ui.components.ConfirmDialog
import com.homestock.ui.components.rememberConfirmHaptic
import com.homestock.domain.model.Categories

private enum class CameraTarget { NONE, OBJET, EMPLACEMENT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AddObjetScreen(
    onDone: () -> Unit,
    viewModel: AddObjetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var cameraTarget by remember { mutableStateOf(CameraTarget.NONE) }
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val confirmHaptic = rememberConfirmHaptic()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(cameraTarget) {
        if (cameraTarget != CameraTarget.NONE && !cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    if (cameraTarget != CameraTarget.NONE && cameraPermission.status.isGranted) {
        CameraCapture(onCaptured = { bytes ->
            when (cameraTarget) {
                CameraTarget.OBJET -> viewModel.uploadObjetPhoto(bytes)
                CameraTarget.EMPLACEMENT -> viewModel.uploadEmplacementPhoto(bytes)
                CameraTarget.NONE -> {}
            }
            cameraTarget = CameraTarget.NONE
        })
        return
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    val verb = if (state.isEditing) "Modifier" else "Ajouter"
                    Text("$verb un objet — étape ${state.step + 1}/4")
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.step == 0) onDone() else viewModel.prevStep() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.step > 0) {
                    OutlinedButton(onClick = viewModel::prevStep, modifier = Modifier.weight(1f)) {
                        Text("Précédent")
                    }
                }
                if (state.step < 3) {
                    Button(onClick = viewModel::nextStep, modifier = Modifier.weight(1f)) {
                        Text("Suivant")
                    }
                } else {
                    Button(
                        onClick = { viewModel.save { confirmHaptic(); onDone() } },
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        val label = when {
                            state.saving -> "Enregistrement…"
                            state.isEditing -> "Mettre à jour"
                            else -> "Enregistrer"
                        }
                        Text(label)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            LinearProgressIndicator(
                progress = (state.step + 1) / 4f,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            when (state.step) {
                0 -> StepObjet(viewModel, state) { cameraTarget = CameraTarget.OBJET }
                1 -> StepLocalisation(viewModel, state) { cameraTarget = CameraTarget.EMPLACEMENT }
                2 -> StepDetails(viewModel, state)
                else -> StepConfirmation(viewModel, state)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Supprimer « ${state.nom.ifBlank { "cet objet" }} » ?",
            message = "Cette action est définitive.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = {
                confirmHaptic()
                showDeleteConfirm = false
                viewModel.delete(onDone)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
