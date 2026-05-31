package com.homestock.ui.screens.zones

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.data.local.EmplacementEntity
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.ObjetResultCard
import com.homestock.ui.components.SectionHeader
import com.homestock.util.matchesAllTerms

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneDetailScreen(
    onBack: () -> Unit,
    onObjet: (Long) -> Unit,
    connected: Boolean = true,
    viewModel: ZoneDetailViewModel = hiltViewModel(),
) {
    val objets by viewModel.objets.collectAsStateWithLifecycle()
    val emplacements by viewModel.emplacements.collectAsStateWithLifecycle()
    val zoneNom by viewModel.zoneNom.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEmp by remember { mutableStateOf<EmplacementEntity?>(null) }
    var confirmDeleteEmp by remember { mutableStateOf<EmplacementEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    val categories = remember(objets) { objets.map { it.categorie }.distinct().sorted() }
    val filtered = objets.filter { o ->
        (categoryFilter == null || o.categorie == categoryFilter) &&
            (searchQuery.isBlank() || matchesAllTerms(
                listOfNotNull(o.nom, o.sousCategorie, o.notes).joinToString(" "),
                searchQuery,
            ))
    }
    val empName = emplacements.associate { it.id to it.nomEmplacement }
    val empPhoto = emplacements.associate { it.id to it.photoUrl }
    val grouped = filtered.groupBy { it.emplacementId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(zoneNom) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = { ConnectionDot(connected, Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("Rechercher dans cette zone…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
            if (categories.isNotEmpty()) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                    ) {
                        FilterChip(
                            selected = categoryFilter == null,
                            onClick = { categoryFilter = null },
                            label = { Text("Tout") },
                        )
                        Spacer(Modifier.width(8.dp))
                        categories.forEach { cat ->
                            FilterChip(
                                selected = categoryFilter == cat,
                                onClick = { categoryFilter = cat },
                                label = { Text(cat) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }

            // Emplacement management section — surfaces the add/edit/delete
            // affordances directly inside the zone, so the user doesn't have
            // to dig into Settings to manage the storage hierarchy.
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Emplacements",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Nouveau")
                    }
                }
            }
            items(emplacements) { emp ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editingEmp = emp }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(emp.nomEmplacement, Modifier.weight(1f))
                    val count = objets.count { it.emplacementId == emp.id }
                    Text(
                        "$count",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Modifier",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            grouped.forEach { (empId, items) ->
                item { SectionHeader(empName[empId] ?: "Emplacement") }
                items(items) { o ->
                    ObjetResultCard(
                        nom = o.nom, categorie = o.categorie,
                        zoneNom = zoneNom, emplacementNom = empName[o.emplacementId],
                        photoUrl = viewModel.photoUrl(o.photoUrl),
                        quantite = o.quantite, unite = o.unite, etat = o.etat,
                        dateExpiration = o.dateExpiration,
                        emplacementPhotoUrl = viewModel.photoUrl(empPhoto[o.emplacementId]),
                        onClick = { onObjet(o.localId) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Nouvel emplacement") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        viewModel.addEmplacement(newName)
                        showAddDialog = false
                    },
                ) { Text("Créer") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Annuler") }
            },
        )
    }

    editingEmp?.let { emp ->
        var editedName by remember(emp.id) { mutableStateOf(emp.nomEmplacement) }
        AlertDialog(
            onDismissRequest = { editingEmp = null },
            title = { Text(emp.nomEmplacement) },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Nom de l'emplacement") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameEmplacement(emp, editedName)
                    editingEmp = null
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            confirmDeleteEmp = emp
                            editingEmp = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Supprimer") }
                    TextButton(onClick = { editingEmp = null }) { Text("Annuler") }
                }
            },
        )
    }

    confirmDeleteEmp?.let { emp ->
        DeleteEmplacementDialog(
            emp = emp,
            otherEmps = emplacements.filter { it.id != emp.id },
            loadObjetsCount = { viewModel.objetsCount(emp.id) },
            onDeleteEmpty = {
                viewModel.deleteEmplacement(emp)
                confirmDeleteEmp = null
            },
            onMigrateAndDelete = { targetId ->
                viewModel.migrateAndDeleteEmplacement(emp, targetId)
                confirmDeleteEmp = null
            },
            onDismiss = { confirmDeleteEmp = null },
        )
    }
}

/**
 * Mirror of the zone delete dialog from Settings, scoped to emplacements.
 * Empty emplacement → simple destructive confirmation. Non-empty → forces
 * the user to choose a target emplacement so the server-side migration
 * endpoint moves objets out before the source is removed.
 */
@Composable
private fun DeleteEmplacementDialog(
    emp: EmplacementEntity,
    otherEmps: List<EmplacementEntity>,
    loadObjetsCount: suspend () -> Int,
    onDeleteEmpty: () -> Unit,
    onMigrateAndDelete: (targetId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember(emp.id) { mutableStateOf<Int?>(null) }
    var targetId by remember(emp.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(emp.id) {
        count = runCatching { loadObjetsCount() }.getOrDefault(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer « ${emp.nomEmplacement} » ?") },
        text = {
            val n = count
            when {
                n == null -> Text("Analyse du contenu…")
                n == 0 -> Text("Cet emplacement est vide. La suppression est définitive.")
                otherEmps.isEmpty() -> Text(
                    "Cet emplacement contient $n objet${if (n > 1) "s" else ""}, mais " +
                        "aucun autre emplacement n'existe dans cette zone pour les " +
                        "accueillir. Créez-en un d'abord.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Cet emplacement contient $n objet${if (n > 1) "s" else ""}. " +
                            "Choisissez l'emplacement qui les accueillera :",
                    )
                    otherEmps.forEach { candidate ->
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
                            Text(candidate.nomEmplacement)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val n = count
            when {
                n == null -> TextButton(onClick = onDismiss) { Text("Annuler") }
                n == 0 -> TextButton(
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
            if (count != null) TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
