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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.ui.components.ObjetResultCard
import com.homestock.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneDetailScreen(
    onBack: () -> Unit,
    onObjet: (Long) -> Unit,
    viewModel: ZoneDetailViewModel = hiltViewModel(),
) {
    val objets by viewModel.objets.collectAsStateWithLifecycle()
    val emplacements by viewModel.emplacements.collectAsStateWithLifecycle()
    val zoneNom by viewModel.zoneNom.collectAsStateWithLifecycle()
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    val categories = remember(objets) { objets.map { it.categorie }.distinct().sorted() }
    val filtered = objets.filter { categoryFilter == null || it.categorie == categoryFilter }
    val empName = emplacements.associate { it.id to it.nomEmplacement }
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
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
                        onClick = { onObjet(o.localId) },
                    )
                }
            }
        }
    }
}
