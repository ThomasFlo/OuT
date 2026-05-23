package com.homestock.ui.screens.setup

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("8080") }
    var user1 by remember { mutableStateOf("") }
    var user2 by remember { mutableStateOf("") }
    var currentUser by remember { mutableStateOf("") }
    val testResult by viewModel.testResult.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Bienvenue dans HomeStock", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Configurez la connexion à votre NAS et les deux profils.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Text("Serveur NAS", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Adresse IP du NAS") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                viewModel.testConnection(host, port.toIntOrNull() ?: 8080)
            }) { Text("Tester la connexion") }
            Spacer(Modifier.width(12.dp))
            testResult?.let {
                Text(
                    if (it) "✓ Connecté" else "✗ Échec",
                    color = if (it) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Profils du couple", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = user1, onValueChange = { user1 = it; if (currentUser.isBlank()) currentUser = it },
            label = { Text("Prénom 1") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user2, onValueChange = { user2 = it },
            label = { Text("Prénom 2") }, modifier = Modifier.fillMaxWidth(),
        )

        if (user1.isNotBlank() || user2.isNotBlank()) {
            Text("Profil courant", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(user1, user2).filter { it.isNotBlank() }.forEach { name ->
                    val selected = currentUser == name
                    if (selected) {
                        Button(onClick = { currentUser = name }) { Text(name) }
                    } else {
                        OutlinedButton(onClick = { currentUser = name }) { Text(name) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.completeSetup(
                    host = host,
                    port = port.toIntOrNull() ?: 8080,
                    user1 = user1.ifBlank { "Utilisateur 1" },
                    user2 = user2.ifBlank { "Utilisateur 2" },
                    currentUser = currentUser.ifBlank { user1.ifBlank { "Utilisateur 1" } },
                )
            },
            enabled = host.isNotBlank() && user1.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Terminer la configuration") }
        Spacer(Modifier.height(24.dp))
    }
}
