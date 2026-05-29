package com.homestock.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.homestock.domain.model.Categories
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.rememberConfirmHaptic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjetDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    connected: Boolean = true,
    viewModel: ObjetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val objet = state.objet
    val confirmHaptic = rememberConfirmHaptic()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(objet?.nom ?: "Objet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    ConnectionDot(connected, Modifier.padding(end = 8.dp))
                    objet?.let { o ->
                        IconButton(onClick = { onEdit(o.localId) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Modifier")
                        }
                    }
                    IconButton(onClick = { confirmHaptic(); viewModel.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                    }
                },
            )
        },
    ) { padding ->
        if (objet == null) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {}
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Location, prominent.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    buildString {
                        append(state.zoneNom ?: "—")
                        if (!state.emplacementNom.isNullOrBlank()) append("  →  ${state.emplacementNom}")
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            DetailRow("Catégorie", objet.categorie)
            objet.sousCategorie?.let { DetailRow("Sous-catégorie", it) }
            objet.quantite?.let { DetailRow("Quantité", "$it ${objet.unite ?: ""}") }
            objet.etat?.let { DetailRow("État", it) }
            objet.notes?.let { DetailRow("Notes", it) }
            objet.ajoutePar?.let { DetailRow("Ajouté par", it) }

            viewModel.photoUrl(objet.photoUrl)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }

            if (objet.categorie == Categories.WINE) {
                Spacer(Modifier.height(8.dp))
                Text("Vin", style = MaterialTheme.typography.titleMedium)
                objet.vinAppellation?.let { DetailRow("Appellation", it) }
                objet.vinDomaine?.let { DetailRow("Domaine", it) }
                objet.vinMillesime?.let { DetailRow("Millésime", it.toString()) }
                objet.vinType?.let { DetailRow("Type", it) }
                objet.vinNombreBouteilles?.let { DetailRow("Bouteilles", it.toString()) }
                Button(onClick = { confirmHaptic(); viewModel.openBottle() }) {
                    Text("Je débouche une bouteille")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text("$label : ", fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}
