package com.homestock.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.homestock.data.remote.dto.CategoryDto
import com.homestock.ui.components.ReorderableColumn
import com.homestock.ui.components.ZONE_COLORS
import com.homestock.ui.components.ZoneIcons
import com.homestock.ui.components.parseColor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
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
    var newCategory by remember { mutableStateOf("") }
    var editingCategory by remember { mutableStateOf<CategoryDto?>(null) }
    var confirmDeleteCategory by remember { mutableStateOf<CategoryDto?>(null) }

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
        Text(
            "Touchez une zone pour la renommer ou la supprimer. " +
                "Maintenez l'icône ⋮⋮ pour la réordonner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReorderableColumn(
            items = zones,
            key = { it.id },
            onReorder = { viewModel.reorderZones(it.map(ZoneEntity::id)) },
        ) { zone, dragHandle ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingZone = zone }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Réordonner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandle.padding(end = 8.dp),
                )
                Text(
                    zone.nom,
                    Modifier.weight(1f),
                    color = if (zone.actif) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("${zone.nbObjets}")
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { editingZone = zone }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Modifier",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Switch(checked = zone.actif, onCheckedChange = { viewModel.toggleZone(zone) })
            }
        }

        HorizontalDivider()
        Text("Gestion des catégories", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newCategory, onValueChange = { newCategory = it },
                label = { Text("Nouvelle catégorie") }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (newCategory.isNotBlank()) { viewModel.addCategory(newCategory); newCategory = "" }
                },
            ) { Text("Ajouter") }
        }
        Text(
            "Touchez une catégorie pour la renommer ou la supprimer. " +
                "Maintenez l'icône ⋮⋮ pour la réordonner. " +
                "Les catégories système (🔒) ne sont pas modifiables.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReorderableColumn(
            items = categories,
            key = { it.id },
            onReorder = { viewModel.reorderCategories(it.map(CategoryDto::id)) },
        ) { category, dragHandle ->
            val editable = !category.protegee
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (editable) Modifier.clickable { editingCategory = category }
                        else Modifier,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Réordonner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandle.padding(end = 8.dp),
                )
                Text(
                    if (category.protegee) "${category.nom}  🔒" else category.nom,
                    Modifier.weight(1f),
                )
                Text("${category.nbObjets}")
                Spacer(Modifier.width(8.dp))
                if (editable) {
                    IconButton(onClick = { editingCategory = category }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Modifier",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
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

        HorizontalDivider()
        VersionSection()
        Spacer(Modifier.height(24.dp))
    }

    editingZone?.let { zone ->
        var editedName by remember(zone.id) { mutableStateOf(zone.nom) }
        var editedIcon by remember(zone.id) { mutableStateOf(zone.icone) }
        var editedColor by remember(zone.id) { mutableStateOf(zone.couleur) }
        AlertDialog(
            onDismissRequest = { editingZone = null },
            title = { Text("Zone : ${zone.nom}") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Nom de la zone") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ZoneAppearancePicker(
                        selectedIcon = editedIcon,
                        selectedColor = editedColor,
                        onIconSelected = { editedIcon = it },
                        onColorSelected = { editedColor = it },
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
                        viewModel.updateZoneDetails(zone, editedName, editedIcon, editedColor)
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

    editingCategory?.let { category ->
        var editedName by remember(category.id) { mutableStateOf(category.nom) }
        AlertDialog(
            onDismissRequest = { editingCategory = null },
            title = { Text(category.nom) },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Nom de la catégorie") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCategory(category, editedName)
                    editingCategory = null
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            confirmDeleteCategory = category
                            editingCategory = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Supprimer") }
                    TextButton(onClick = { editingCategory = null }) { Text("Annuler") }
                }
            },
        )
    }

    confirmDeleteCategory?.let { category ->
        DeleteCategoryDialog(
            category = category,
            otherCategories = categories.filter { it.id != category.id },
            onDeleteEmpty = {
                viewModel.deleteCategory(category)
                confirmDeleteCategory = null
            },
            onMigrateAndDelete = { targetId ->
                viewModel.migrateAndDeleteCategory(category, targetId)
                confirmDeleteCategory = null
            },
            onDismiss = { confirmDeleteCategory = null },
        )
    }
}

/**
 * Confirmation dialog for deleting a category.
 *
 * Categories carry their object count in the DTO (no extra lookup needed).
 * Empty category → simple destructive confirmation. Non-empty → forces the
 * user to pick a target category onto which the objects are reassigned
 * before the source is removed.
 */
@Composable
private fun DeleteCategoryDialog(
    category: CategoryDto,
    otherCategories: List<CategoryDto>,
    onDeleteEmpty: () -> Unit,
    onMigrateAndDelete: (targetId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var targetId by remember(category.id) { mutableStateOf<Long?>(null) }
    val count = category.nbObjets

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer « ${category.nom} » ?") },
        text = {
            when {
                count == 0 -> Text("Cette catégorie est vide. La suppression est définitive.")
                otherCategories.isEmpty() -> Text(
                    "Cette catégorie contient $count objet${if (count > 1) "s" else ""}, " +
                        "mais aucune autre catégorie n'existe pour les accueillir.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Cette catégorie contient $count objet${if (count > 1) "s" else ""}. " +
                            "Choisissez la catégorie qui les accueillera :",
                    )
                    otherCategories.forEach { candidate ->
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
            when {
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
                ) { Text("Réaffecter et supprimer") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
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

/**
 * Shows the running app version and what the NAS believes the latest
 * published version is. Lets the user see at a glance whether they are up
 * to date without waiting for the start-up update prompt to fire again.
 */
@Composable
private fun VersionSection(viewModel: SettingsViewModel = hiltViewModel()) {
    val server by viewModel.serverVersion.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Version", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = viewModel::refreshServerVersion) {
            Icon(Icons.Filled.Refresh, contentDescription = "Rafraîchir")
        }
    }
    Text(
        "Application : ${viewModel.appVersionName} (build ${viewModel.appVersionCode})",
        style = MaterialTheme.typography.bodyMedium,
    )
    val s = server
    when {
        s == null -> Text(
            "Serveur NAS : indisponible ou aucune APK publiée.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.versionCode > viewModel.appVersionCode -> Text(
            "Serveur NAS : ${s.versionName} (build ${s.versionCode}) — mise à jour disponible.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        s.versionCode < viewModel.appVersionCode -> Text(
            "Serveur NAS : ${s.versionName} (build ${s.versionCode}) — votre app est plus récente que celle publiée.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            "Serveur NAS : ${s.versionName} (build ${s.versionCode}) — à jour.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * Icon + colour picker for a zone, used inside the edit dialog. A live preview
 * (the chosen icon on the chosen colour) sits above two wrapping rows of
 * choices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneAppearancePicker(
    selectedIcon: String,
    selectedColor: String,
    onIconSelected: (String) -> Unit,
    onColorSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(parseColor(selectedColor)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ZoneIcons.vectorFor(selectedIcon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Aperçu", style = MaterialTheme.typography.bodyMedium)
        }

        Text("Icône", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZoneIcons.CATALOG.forEach { item ->
                val selected = item.key == selectedIcon
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { onIconSelected(item.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        item.vector,
                        contentDescription = item.label,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Text("Couleur", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZONE_COLORS.forEach { hex ->
                val selected = hex.equals(selectedColor, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(parseColor(hex))
                        .then(
                            if (selected) Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape,
                            ) else Modifier,
                        )
                        .clickable { onColorSelected(hex) },
                )
            }
        }
    }
}
