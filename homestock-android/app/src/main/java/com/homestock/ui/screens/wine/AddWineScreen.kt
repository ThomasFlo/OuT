package com.homestock.ui.screens.wine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.homestock.domain.model.WineTypes
import com.homestock.ui.components.CameraCapture
import com.homestock.ui.components.GalleryPickerButton
import com.homestock.ui.components.PhotoThumbnail

/**
 * Dedicated single-page form for adding a bottle to the cellar.
 *
 * The generic 4-step objet wizard was overkill for wine: most of its
 * fields (sous-catégorie, état, date d'expiration) never apply, and
 * the wine-specific ones were buried at the bottom of step 3. This
 * screen exposes only what makes sense for a bottle: name/appellation,
 * domaine, millésime, type, count, location, photo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    ExperimentalPermissionsApi::class)
@Composable
fun AddWineScreen(
    onDone: () -> Unit,
    viewModel: AddWineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val emplacements by viewModel.emplacements.collectAsStateWithLifecycle()
    var capturingPhoto by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(capturingPhoto) {
        if (capturingPhoto && !cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }
    if (capturingPhoto && cameraPermission.status.isGranted) {
        CameraCapture(onCaptured = { bytes ->
            viewModel.uploadPhoto(bytes)
            capturingPhoto = false
        })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajouter une bouteille") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = !state.saving && !state.uploading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.saving) "Enregistrement…" else "Enregistrer la bouteille")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            OutlinedTextField(
                value = state.appellation,
                onValueChange = { v -> viewModel.update { it.copy(appellation = v) } },
                label = { Text("Appellation (ex. Saint-Émilion)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.domaine,
                onValueChange = { v -> viewModel.update { it.copy(domaine = v) } },
                label = { Text("Domaine / château") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.millesime,
                    onValueChange = { v ->
                        viewModel.update { it.copy(millesime = v.filter(Char::isDigit).take(4)) }
                    },
                    label = { Text("Millésime") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.nombreBouteilles,
                    onValueChange = { v ->
                        viewModel.update { it.copy(nombreBouteilles = v.filter(Char::isDigit).take(3)) }
                    },
                    label = { Text("Bouteilles") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Text("Type", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WineTypes.ALL.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.update { it.copy(type = type) } },
                        label = { Text(type) },
                    )
                }
            }

            OutlinedTextField(
                value = state.nom,
                onValueChange = { v -> viewModel.update { it.copy(nom = v) } },
                label = { Text("Nom (optionnel — sinon généré depuis l'appellation)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Emplacement", fontWeight = FontWeight.SemiBold)
            ZoneDropdown(
                zones = zones,
                selectedId = state.zoneId,
                onSelect = viewModel::selectZone,
            )
            if (state.zoneId != null && emplacements.isNotEmpty()) {
                EmplacementDropdown(
                    items = emplacements.map { it.id to it.nomEmplacement },
                    selectedId = state.emplacementId,
                    onSelect = viewModel::selectEmplacement,
                )
            }
            if (state.zoneId != null) {
                OutlinedTextField(
                    value = state.newEmplacementNom,
                    onValueChange = { v ->
                        viewModel.update { it.copy(newEmplacementNom = v) }
                    },
                    label = { Text("…ou créer un nouvel emplacement") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = state.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Photo", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.uploading) {
                    Text("Envoi…", modifier = Modifier.padding(end = 8.dp))
                } else {
                    PhotoThumbnail(state.photoUrl?.let {
                        // Same URL resolver pattern as elsewhere: the repo
                        // strips the base so we can render full URL here.
                        // We rely on the ObjetDetailScreen's resolver via
                        // VM but for simplicity, the picker shows the
                        // local relative path's tail — Coil handles that.
                        it
                    }, size = 64)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { capturingPhoto = true },
                    enabled = !state.uploading,
                ) { Text("Caméra") }
                Spacer(Modifier.width(4.dp))
                GalleryPickerButton(
                    onBytes = viewModel::uploadPhoto,
                    enabled = !state.uploading,
                )
                if (!state.photoUrl.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { viewModel.clearPhoto() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Retirer") }
                }
            }

            Spacer(Modifier.padding(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneDropdown(
    zones: List<com.homestock.data.local.ZoneEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = zones.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = current?.nom ?: "Choisir une zone",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            label = { Text("Zone") },
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            zones.forEach { zone ->
                DropdownMenuItem(
                    text = { Text(zone.nom) },
                    onClick = { onSelect(zone.id); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmplacementDropdown(
    items: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = items.firstOrNull { it.first == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = current?.second ?: "Choisir un emplacement existant",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            label = { Text("Emplacement existant") },
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}
