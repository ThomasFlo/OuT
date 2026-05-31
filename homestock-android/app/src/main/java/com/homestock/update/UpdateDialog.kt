package com.homestock.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Drop-in composable that performs a one-shot update check at composition
 * and displays a dialog walking the user through download → install when a
 * newer release is published on the NAS.
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkOnce() }

    when (val s = state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Mise à jour disponible") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Version ${s.info.versionName} (build ${s.info.versionCode}) " +
                            "— actuellement ${viewModel.currentVersionName} " +
                            "(build ${viewModel.currentVersionCode}).",
                    )
                    s.info.sizeBytes?.let { Text("Taille : ${humanSize(it)}") }
                    s.info.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.download(s.info) }) { Text("Télécharger") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Plus tard") }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Téléchargement…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HomeStock ${s.info.versionName}")
                    LinearProgressIndicator(modifier = Modifier)
                }
            },
            confirmButton = {},
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Prêt à installer") },
            text = {
                Text(
                    if (viewModel.canInstallPackages()) {
                        "Le téléchargement est terminé et vérifié. " +
                            "L'installeur Android va s'ouvrir."
                    } else {
                        "Pour installer la mise à jour, autorisez HomeStock à " +
                            "installer des applications dans les paramètres système. " +
                            "Vous reviendrez ensuite ici."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.canInstallPackages()) {
                        viewModel.install(s.file)
                        viewModel.dismiss()
                    } else {
                        viewModel.openInstallSettings()
                    }
                }) {
                    Text(if (viewModel.canInstallPackages()) "Installer" else "Ouvrir les paramètres")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Annuler") }
            },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Mise à jour impossible") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Fermer") }
            },
        )
    }
}

private fun humanSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "%.1f Mo".format(mb) else "%.0f Ko".format(bytes / 1024.0)
}
