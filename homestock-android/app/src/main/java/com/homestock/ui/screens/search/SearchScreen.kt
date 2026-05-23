package com.homestock.ui.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.MicButton
import com.homestock.ui.components.ObjetResultCard
import com.homestock.ui.components.SectionHeader
import com.homestock.ui.components.ZoneCard

@Composable
fun SearchScreen(
    connected: Boolean,
    onObjet: (Long) -> Unit,
    onAdd: () -> Unit,
    onAddVoice: (String?, Long?, String?, Int?) -> Unit,
    onRetrySync: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val expiring by viewModel.expiringSoon.collectAsStateWithLifecycle()
    val voiceLang by viewModel.voiceLanguage.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header with connection status.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("HomeStock", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                ConnectionDot(connected)
            }

            // Prominent search bar with mic.
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Rechercher un objet…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    MicButton(
                        language = voiceLang,
                        onResult = { text ->
                            when (val intent = viewModel.classifyVoice(text)) {
                                is VoiceIntent.Search -> viewModel.search(intent.query)
                                is VoiceIntent.Add -> onAddVoice(
                                    intent.command.nom,
                                    intent.command.zoneId,
                                    intent.command.emplacement,
                                    intent.command.quantite,
                                )
                            }
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )

            Spacer(Modifier.height(8.dp))

            if (query.isNotBlank()) {
                SearchResults(searching, results, viewModel, onObjet)
            } else {
                Dashboard(zones, recent, expiring, viewModel, onObjet)
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Ajouter") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun SearchResults(
    searching: Boolean,
    results: List<com.homestock.domain.model.SearchResult>,
    viewModel: SearchViewModel,
    onObjet: (Long) -> Unit,
) {
    if (searching) {
        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (results.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
            Text("Aucun résultat", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results) { r ->
            ObjetResultCard(
                nom = r.objet.nom,
                categorie = r.objet.categorie,
                zoneNom = r.zoneNom,
                emplacementNom = r.emplacementNom,
                photoUrl = viewModel.photoUrl(r.objet.photoUrl),
                quantite = r.objet.quantite,
                unite = r.objet.unite,
                etat = r.objet.etat,
                onClick = {
                    val id = r.objet.localId.takeIf { it != 0L }
                    if (id != null) onObjet(id)
                },
            )
        }
    }
}

@Composable
private fun Dashboard(
    zones: List<com.homestock.data.local.ZoneEntity>,
    recent: List<com.homestock.data.local.ObjetEntity>,
    expiring: List<com.homestock.data.local.ObjetEntity>,
    viewModel: SearchViewModel,
    onObjet: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (expiring.isNotEmpty()) {
            item { SectionHeader("Bientôt périmés") }
            items(expiring) { o ->
                ObjetResultCard(
                    nom = o.nom, categorie = o.categorie, zoneNom = null,
                    emplacementNom = null, photoUrl = viewModel.photoUrl(o.photoUrl),
                    quantite = o.quantite, unite = o.unite, etat = "⚠ Expiration proche",
                    onClick = { onObjet(o.localId) },
                )
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("Récemment ajoutés") }
            items(recent) { o ->
                ObjetResultCard(
                    nom = o.nom, categorie = o.categorie, zoneNom = null,
                    emplacementNom = null, photoUrl = viewModel.photoUrl(o.photoUrl),
                    quantite = o.quantite, unite = o.unite, etat = o.etat,
                    onClick = { onObjet(o.localId) },
                )
            }
        }
        item { SectionHeader("Zones") }
        items(zones) { z ->
            ZoneCard(z.nom, z.couleur, z.nbObjets, onClick = {})
        }
    }
}
