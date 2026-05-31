package com.homestock.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.data.local.ZoneEntity
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val current = settings ?: return
    var host by remember(current.nasHost) { mutableStateOf(current.nasHost) }
    var port by remember(current.nasPort) { mutableStateOf(current.nasPort.toString()) }
    var newZone by remember { mutableStateOf("") }
    var editingZone by remember { mutableStateOf<ZoneEntity?>(null) }
    var confirmDeleteZone by remember { mutableStateOf<ZoneEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    // Export: write the JSON dump to a user-chosen file.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val json = viewModel.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            Toast.makeText(context, "Export terminé", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) viewModel.importJson(text)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Paramètres", style = MaterialTheme.typography.headlineMedium)

        Text("Serveur NAS", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Adresse IP") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                viewModel.saveNas(host, port.toIntOrNull() ?: 8080)
            }) { Text("Enregistrer") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = viewModel::testConnection) { Text("Tester") }
            Spacer(Modifier.width(8.dp))
            testResult?.let {
                Text(if (it) "✓" else "✗", color = if (it) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            }
        }

        HorizontalDivider()
        Text("Profil courant", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(current.user1, current.user2).filter { it.isNotBlank() }.forEach { name ->
                if (current.currentUser == name) {
                    Button(onClick = { viewModel.setCurrentUser(name) }) { Text(name) }
                } else {
                    OutlinedButton(onClick = { viewModel.setCurrentUser(name) }) { Text(name) }
                }
            }
        }

        HorizontalDivider()
        Text("Reconnaissance vocale", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("fr-FR" to "Français", "en-US" to "English").forEach { (code, label) ->
                if (current.voiceLanguage == code) {
                    Button(onClick = { viewModel.setVoiceLanguage(code) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { viewModel.setVoiceLanguage(code) }) { Text(label) }
                }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications d'expiration", Modifier.weight(1f))
            Switch(
                checked = current.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode debug (scores de recherche)", Modifier.weight(1f))
            Switch(
                checked = current.debugMode,
                onCheckedChange = viewModel::setDebugMode,
            )
        }

        HorizontalDivider()
        Text("Gestion des zones", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newZone, onValueChange = { newZone = it },
                label = { Text("Nouvelle zone") }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (newZone.isNotBlank()) { viewModel.addZone(newZone); newZone = "" } },
            ) { Text("Ajouter") }
        }
        zones.forEach { zone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    zone.nom,
                    Modifier
                        .weight(1f)
                        .clickable { editingZone = zone }
                        .padding(vertical = 8.dp),
                    color = if (zone.actif) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("${zone.nbObjets}")
                Spacer(Modifier.width(8.dp))
                Switch(checked = zone.actif, onCheckedChange = { viewModel.toggleZone(zone) })
            }
        }

        HorizontalDivider()
        Text("Sauvegarde", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("homestock-export.json") }) {
                Text("Exporter (JSON)")
            }
            OutlinedButton(onClick = { importLauncher.launch("application/json") }) {
                Text("Importer (JSON)")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editingZone?.let { zone ->
        var editedName by remember(zone.id) { mutableStateOf(zone.nom) }
        AlertDialog(
            onDismissRequest = { editingZone = null },
            title = { Text("Zone : ${zone.nom}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Nom de la zone") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${zone.nbObjets} objet${if (zone.nbObjets > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editedName.trim()
                        if (trimmed.isNotBlank() && trimmed != zone.nom) {
                            viewModel.renameZone(zone, trimmed)
                        }
                        editingZone = null
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            confirmDeleteZone = zone
                            editingZone = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Supprimer") }
                    TextButton(onClick = { editingZone = null }) { Text("Annuler") }
                }
            },
        )
    }

    confirmDeleteZone?.let { zone ->
        DeleteZoneDialog(
            zone = zone,
            otherZones = zones.filter { it.id != zone.id },
            loadEmpCount = { viewModel.emplacementsCount(zone.id) },
            onDeleteEmpty = {
                viewModel.deleteZone(zone)
                confirmDeleteZone = null
            },
            onMigrateAndDelete = { targetId ->
                viewModel.migrateAndDeleteZone(zone, targetId)
                confirmDeleteZone = null
            },
            onDismiss = { confirmDeleteZone = null },
        )
    }
}

/**
 * Confirmation dialog for deleting a zone.
 *
 * When the source zone is empty, it acts as a regular destructive confirmation.
 * When the source zone contains emplacements, the user MUST pick a target zone
 * onto which the emplacements are migrated before the source is deleted —
 * the server refuses a destructive delete on a non-empty zone, so this UI is
 * the only safe path.
 */
@Composable
private fun DeleteZoneDialog(
    zone: ZoneEntity,
    otherZones: List<ZoneEntity>,
    loadEmpCount: suspend () -> Int,
    onDeleteEmpty: () -> Unit,
    onMigrateAndDelete: (targetId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var empCount by remember(zone.id) { mutableStateOf<Int?>(null) }
    var targetId by remember(zone.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(zone.id) {
        empCount = runCatching { loadEmpCount() }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer « ${zone.nom} » ?") },
        text = {
            val count = empCount
            when {
                count == null -> Text("Analyse du contenu…")
                count == 0 -> Text("Cette zone est vide. La suppression est définitive.")
                otherZones.isEmpty() -> Text(
                    "Cette zone contient $count emplacement${if (count > 1) "s" else ""}, " +
                        "mais aucune autre zone n'existe pour les accueillir. " +
                        "Créez d'abord une nouvelle zone.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Cette zone contient $count emplacement${if (count > 1) "s" else ""} " +
                            "et ${zone.nbObjets} objet${if (zone.nbObjets > 1) "s" else ""}. " +
                            "Choisissez la zone qui les accueillera :",
                    )
                    otherZones.forEach { candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { targetId = candidate.id },
                        ) {
                            RadioButton(
                                selected = targetId == candidate.id,
                                onClick = { targetId = candidate.id },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(candidate.nom)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val count = empCount
            when {
                count == null -> TextButton(onClick = onDismiss) { Text("Annuler") }
                count == 0 -> TextButton(
                    onClick = onDeleteEmpty,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Supprimer") }
                else -> TextButton(
                    enabled = targetId != null,
                    onClick = { targetId?.let(onMigrateAndDelete) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Déplacer et supprimer") }
            }
        },
        dismissButton = {
            if (empCount != null) TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
