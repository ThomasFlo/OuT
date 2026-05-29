package com.homestock.ui.screens.zones

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

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
}
