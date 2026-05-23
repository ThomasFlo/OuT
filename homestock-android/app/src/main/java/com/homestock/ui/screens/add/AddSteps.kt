package com.homestock.ui.screens.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.domain.model.Categories
import com.homestock.domain.model.EtatOptions
import com.homestock.domain.model.WineTypes
import com.homestock.ui.components.PhotoThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

@Composable
fun StepObjet(viewModel: AddObjetViewModel, state: AddFormState, onCamera: () -> Unit) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("L'objet", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.nom,
            onValueChange = { v -> viewModel.update { it.copy(nom = v) } },
            label = { Text("Nom de l'objet") },
            modifier = Modifier.fillMaxWidth(),
        )
        Dropdown("Catégorie", state.categorie, categories.ifEmpty { Categories.ALL }) { v ->
            viewModel.update { it.copy(categorie = v) }
        }
        OutlinedTextField(
            value = state.sousCategorie,
            onValueChange = { v -> viewModel.update { it.copy(sousCategorie = v) } },
            label = { Text("Sous-catégorie (optionnel)") },
            modifier = Modifier.fillMaxWidth(),
        )
        PhotoRow(
            label = "Photo de l'objet",
            url = viewModel.photoUrl(state.photoObjetUrl),
            uploading = state.uploading,
            onCamera = onCamera,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepLocalisation(viewModel: AddObjetViewModel, state: AddFormState, onCamera: () -> Unit) {
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val emplacements by viewModel.emplacements.collectAsStateWithLifecycle()

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Localisation", style = MaterialTheme.typography.titleLarge)
        Text("Zone", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            zones.forEach { zone ->
                FilterChip(
                    selected = state.zoneId == zone.id,
                    onClick = { viewModel.selectZone(zone.id) },
                    label = { Text(zone.nom) },
                )
            }
        }

        if (state.zoneId != null) {
            Text("Emplacement existant", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emplacements.forEach { emp ->
                    FilterChip(
                        selected = state.emplacementId == emp.id,
                        onClick = {
                            viewModel.update {
                                it.copy(emplacementId = emp.id, newEmplacementNom = "")
                            }
                        },
                        label = { Text(emp.nomEmplacement) },
                    )
                }
            }
            Text("…ou créer un nouvel emplacement", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.newEmplacementNom,
                onValueChange = { v ->
                    viewModel.update { it.copy(newEmplacementNom = v, emplacementId = null) }
                },
                label = { Text("Nom du nouvel emplacement") },
                modifier = Modifier.fillMaxWidth(),
            )
            PhotoRow(
                label = "Photo de l'emplacement",
                url = viewModel.photoUrl(state.photoEmplacementUrl),
                uploading = state.uploading,
                onCamera = onCamera,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepDetails(viewModel: AddObjetViewModel, state: AddFormState) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Détails (optionnels)", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.quantite,
                onValueChange = { v -> viewModel.update { it.copy(quantite = v.filter(Char::isDigit)) } },
                label = { Text("Quantité") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.unite,
                onValueChange = { v -> viewModel.update { it.copy(unite = v) } },
                label = { Text("Unité") },
                modifier = Modifier.weight(1f),
            )
        }
        Dropdown("État", state.etat ?: "", EtatOptions.ALL) { v ->
            viewModel.update { it.copy(etat = v) }
        }
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                state.dateExpiration?.let { "Expire le ${dateFmt.format(Date(it))}" }
                    ?: "Date d'expiration",
            )
        }
        OutlinedTextField(
            value = state.notes,
            onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.categorie == Categories.WINE) {
            WineSubForm(viewModel, state)
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.dateExpiration)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(dateExpiration = pickerState.selectedDateMillis) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Annuler") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun WineSubForm(viewModel: AddObjetViewModel, state: AddFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cave à vins", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.vinAppellation,
            onValueChange = { v -> viewModel.update { it.copy(vinAppellation = v) } },
            label = { Text("Appellation") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.vinDomaine,
            onValueChange = { v -> viewModel.update { it.copy(vinDomaine = v) } },
            label = { Text("Domaine") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.vinMillesime,
                onValueChange = { v -> viewModel.update { it.copy(vinMillesime = v.filter(Char::isDigit)) } },
                label = { Text("Millésime") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.vinNombreBouteilles,
                onValueChange = { v -> viewModel.update { it.copy(vinNombreBouteilles = v.filter(Char::isDigit)) } },
                label = { Text("Bouteilles") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        DropdownInline("Type", state.vinType, WineTypes.ALL) { v ->
            viewModel.update { it.copy(vinType = v) }
        }
        OutlinedTextField(
            value = state.vinEmplacementRangee,
            onValueChange = { v -> viewModel.update { it.copy(vinEmplacementRangee = v) } },
            label = { Text("Rangée") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownInline(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) = Dropdown(label, value, options, onSelect)

@Composable
fun StepConfirmation(viewModel: AddObjetViewModel, state: AddFormState) {
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val emplacements by viewModel.emplacements.collectAsStateWithLifecycle()
    val zoneNom = zones.firstOrNull { it.id == state.zoneId }?.nom ?: "—"
    val empNom = state.emplacementId
        ?.let { id -> emplacements.firstOrNull { it.id == id }?.nomEmplacement }
        ?: state.newEmplacementNom.ifBlank { "—" }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Récapitulatif", style = MaterialTheme.typography.titleLarge)
        ConfRow("Objet", state.nom)
        ConfRow("Catégorie", state.categorie)
        ConfRow("Zone", zoneNom)
        ConfRow("Emplacement", empNom)
        if (state.quantite.isNotBlank()) ConfRow("Quantité", "${state.quantite} ${state.unite}")
        state.etat?.let { ConfRow("État", it) }
        if (state.notes.isNotBlank()) ConfRow("Notes", state.notes)
        PhotoThumbnail(viewModel.photoUrl(state.photoObjetUrl), size = 96)
    }
}

@Composable
private fun ConfRow(label: String, value: String) {
    Row {
        Text("$label : ", fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}

@Composable
private fun PhotoRow(label: String, url: String?, uploading: Boolean, onCamera: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onClick = onCamera) { Text(if (uploading) "Envoi…" else label) }
        Spacer(Modifier.width(12.dp))
        PhotoThumbnail(url, size = 56)
    }
}
