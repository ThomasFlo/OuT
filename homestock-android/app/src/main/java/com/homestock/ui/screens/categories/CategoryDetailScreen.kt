package com.homestock.ui.screens.categories

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.ui.components.ObjetResultCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    onBack: () -> Unit,
    onObjet: (Long) -> Unit,
    viewModel: CategoryDetailViewModel = hiltViewModel(),
) {
    val objets by viewModel.objets.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.categorie) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(objets) { item ->
                ObjetResultCard(
                    nom = item.objet.nom,
                    categorie = item.objet.categorie,
                    zoneNom = item.zoneNom,
                    emplacementNom = item.emplacementNom,
                    photoUrl = viewModel.photoUrl(item.objet.photoUrl),
                    quantite = item.objet.quantite,
                    unite = item.objet.unite,
                    etat = item.objet.etat,
                    onClick = { onObjet(item.objet.localId) },
                )
            }
        }
    }
}
