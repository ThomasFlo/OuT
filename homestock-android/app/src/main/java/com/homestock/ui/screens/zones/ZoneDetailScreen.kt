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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.homestock.data.local.EmplacementEntity
import com.homestock.ui.components.CameraCapture
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.ObjetResultCard
import com.homestock.ui.components.PhotoThumbnail
import com.homestock.ui.components.SectionHeader
import com.homestock.util.matchesAllTerms

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
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
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var confirmDeleteEmp by remember { mutableStateOf<EmplacementEntity?>(null) }
    var capturingPhoto by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(capturingPhoto) {
        if (capturingPhoto && !cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    // Full-screen camera, shown when the user taps "Photo" inside the draft
    // dialog. On capture we hand the bytes to the VM and return to the dialog.
    if (capturingPhoto && cameraPermission.status.isGranted) {
        CameraCapture(onCaptured = { bytes ->
            viewModel.uploadDraftPhoto(bytes)
            capturingPhoto = false
        })
        return
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
                    TextButton(onClick = { viewModel.startCreateEmplacement() }) {
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
                        .clickable { viewModel.startEditEmplacement(emp) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoThumbnail(viewModel.photoUrl(emp.photoUrl), size = 40)
                    if (!emp.photoUrl.isNullOrBlank()) Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(emp.nomEmplacement)
                        emp.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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

    draft?.let { d ->
        EmplacementDraftDialog(
            draft = d,
            photoUrl = viewModel.photoUrl(d.photoUrl),
            isEditing = d.id != null,
            onNameChange = { name -> viewModel.updateDraft { it.copy(nom = name) } },
            onDescriptionChange = { desc -> viewModel.updateDraft { it.copy(description = desc) } },
            onTakePhoto = { capturingPhoto = true },
            onRemovePhoto = { viewModel.removeDraftPhoto() },
            onSave = { viewModel.saveDraft() },
            onDelete = {
                // Switch to the delete flow for the persisted emplacement.
                emplacements.firstOrNull { it.id == d.id }?.let { confirmDeleteEmp = it }
                viewModel.cancelDraft()
            },
            onDismiss = { viewModel.cancelDraft() },
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
 * Create / edit dialog for an emplacement, including an optional photo and
 * free-text description. The photo is captured full-screen (the host screen
 * swaps to the camera), so the draft state lives in the ViewModel to survive
 * that round-trip.
 */
@Composable
private fun EmplacementDraftDialog(
    draft: EmplacementDraft,
    photoUrl: String?,
    isEditing: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Modifier l'emplacement" else "Nouvel emplacement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.nom,
                    onValueChange = onNameChange,
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Description (optionnel)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (draft.uploading) {
                        CircularProgressIndicator(Modifier.size(40.dp))
                    } else if (!photoUrl.isNullOrBlank()) {
                        PhotoThumbnail(photoUrl, size = 56)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = onTakePhoto) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (draft.photoUrl.isNullOrBlank()) "Ajouter une photo" else "Reprendre")
                        }
                        if (!draft.photoUrl.isNullOrBlank()) {
                            TextButton(
                                onClick = onRemovePhoto,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Retirer la photo") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft.nom.isNotBlank() && !draft.uploading, onClick = onSave) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEditing) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Supprimer") }
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
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
