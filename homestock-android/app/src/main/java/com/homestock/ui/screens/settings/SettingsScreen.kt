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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
                    Modifier.weight(1f),
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
}
