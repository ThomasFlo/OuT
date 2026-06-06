package com.homestock.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.homestock.domain.model.Categories
import com.homestock.ui.components.ConfirmDialog
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.ZoneIcons
import com.homestock.ui.components.parseColor
import com.homestock.ui.components.rememberConfirmHaptic

/**
 * Object detail screen, redesigned as a card-based fiche.
 *
 * The hero card prominently shows the location — emplacement photo when
 * available, otherwise a coloured panel using the zone's colour with the
 * zone icon — because "where is it?" is the dominant question.
 * Underneath, an info card lists clickable chips (zone, emplacement,
 * catégorie) that route the user back to the matching listings, plus the
 * secondary attributes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjetDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onZone: (Long) -> Unit = {},
    onCategory: (String) -> Unit = {},
    connected: Boolean = true,
    viewModel: ObjetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val objet = state.objet
    val confirmHaptic = rememberConfirmHaptic()
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                    }
                },
            )
        },
    ) { padding ->
        if (objet == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {}
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocationHero(
                zoneNom = state.zoneNom,
                zoneIcone = state.zoneIcone,
                zoneCouleur = state.zoneCouleur,
                emplacementNom = state.emplacementNom,
                emplacementDescription = state.emplacementDescription,
                emplacementPhotoUrl = viewModel.photoUrl(state.emplacementPhotoUrl),
                onClick = { state.zoneId?.let(onZone) },
            )

            InfoCard(
                nom = objet.nom,
                categorie = objet.categorie,
                sousCategorie = objet.sousCategorie,
                quantite = objet.quantite,
                unite = objet.unite,
                etat = objet.etat,
                notes = objet.notes,
                ajoutePar = objet.ajoutePar,
                zoneNom = state.zoneNom,
                onZoneClick = { state.zoneId?.let(onZone) },
                onCategoryClick = { onCategory(objet.categorie) },
            )

            viewModel.photoUrl(objet.photoUrl)?.let { url ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = "Photo de l'objet",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
            }

            if (objet.categorie == Categories.WINE) {
                WineCard(
                    appellation = objet.vinAppellation,
                    domaine = objet.vinDomaine,
                    millesime = objet.vinMillesime,
                    type = objet.vinType,
                    bouteilles = objet.vinNombreBouteilles,
                    enrichment = state.vinEnrichment,
                    enriching = state.enriching,
                    streamingSummary = state.streamingSummary,
                    onOpenBottle = { confirmHaptic(); viewModel.openBottle() },
                    onEnrich = { viewModel.enrichWine() },
                )
            }
        }
    }

    state.enrichmentError?.let { msg ->
        ConfirmDialog(
            title = "Enrichissement impossible",
            message = msg,
            confirmLabel = "OK",
            destructive = false,
            onConfirm = { viewModel.clearEnrichmentError() },
            onDismiss = { viewModel.clearEnrichmentError() },
        )
    }

    if (showDeleteConfirm && objet != null) {
        ConfirmDialog(
            title = "Supprimer « ${objet.nom} » ?",
            message = "Cette action est définitive.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = {
                confirmHaptic()
                showDeleteConfirm = false
                viewModel.delete(onBack)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Hero card showcasing where the object lives. Falls back gracefully when
 * pieces of info are missing: emplacement photo → coloured panel with zone
 * icon → plain "Sans emplacement" tile. The location text is always overlaid
 * at the bottom for legibility, with a dark gradient when on top of a photo.
 */
@Composable
private fun LocationHero(
    zoneNom: String?,
    zoneIcone: String?,
    zoneCouleur: String?,
    emplacementNom: String?,
    emplacementDescription: String?,
    emplacementPhotoUrl: String?,
    onClick: () -> Unit,
) {
    val zoneColor = zoneCouleur?.let(::parseColor) ?: MaterialTheme.colorScheme.primary
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.height(200.dp)) {
            if (!emplacementPhotoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = emplacementPhotoUrl,
                    contentDescription = "Photo de l'emplacement",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Dark gradient at the bottom so the location text stays readable
                // regardless of the photo's content.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                                startY = 200f,
                            ),
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(zoneColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ZoneIcons.vectorFor(zoneIcone),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(80.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(zoneColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            ZoneIcons.vectorFor(zoneIcone),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        zoneNom ?: "Sans zone",
                        color = if (emplacementPhotoUrl.isNullOrBlank()) Color.White else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
                if (!emplacementNom.isNullOrBlank()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        emplacementNom,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
                if (!emplacementDescription.isNullOrBlank()) {
                    Text(
                        emplacementDescription,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    nom: String,
    categorie: String,
    sousCategorie: String?,
    quantite: Int?,
    unite: String?,
    etat: String?,
    notes: String?,
    ajoutePar: String?,
    zoneNom: String?,
    onZoneClick: () -> Unit,
    onCategoryClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(nom, style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!zoneNom.isNullOrBlank()) {
                    AssistChip(
                        onClick = onZoneClick,
                        label = { Text(zoneNom) },
                        leadingIcon = {
                            Icon(Icons.Filled.Place, contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize))
                        },
                    )
                }
                AssistChip(
                    onClick = onCategoryClick,
                    label = { Text(categorie) },
                    leadingIcon = {
                        Icon(Icons.Filled.Category, contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                )
            }

            sousCategorie?.takeIf { it.isNotBlank() }?.let {
                DetailRow("Sous-catégorie", it)
            }
            quantite?.let {
                DetailRow("Quantité", "$it ${unite.orEmpty()}".trim())
            }
            etat?.takeIf { it.isNotBlank() }?.let { DetailRow("État", it) }
            notes?.takeIf { it.isNotBlank() }?.let { DetailRow("Notes", it) }
            ajoutePar?.takeIf { it.isNotBlank() }?.let { DetailRow("Ajouté par", it) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WineCard(
    appellation: String?,
    domaine: String?,
    millesime: Int?,
    type: String?,
    bouteilles: Int?,
    enrichment: com.homestock.data.remote.dto.VinDto?,
    enriching: Boolean,
    streamingSummary: String?,
    onOpenBottle: () -> Unit,
    onEnrich: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Vin", style = MaterialTheme.typography.titleMedium)
            appellation?.takeIf { it.isNotBlank() }?.let { DetailRow("Appellation", it) }
            domaine?.takeIf { it.isNotBlank() }?.let { DetailRow("Domaine", it) }
            millesime?.let { DetailRow("Millésime", it.toString()) }
            type?.takeIf { it.isNotBlank() }?.let { DetailRow("Type", it) }
            bouteilles?.let { DetailRow("Bouteilles restantes", it.toString()) }

            // While analysing, show the sommelier sentence as Llama types it.
            // The blinking cursor "▍" gives a visible "currently typing" cue.
            if (enriching && !streamingSummary.isNullOrBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "$streamingSummary▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            enrichment?.let { e ->
                e.enrichmentSummary?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                // Break the three drinking dates out as separate rows so the
                // user can read each one at a glance. We label them in the
                // order the user thinks about them: "à partir de quand",
                // "fenêtre idéale", "ne plus boire après".
                e.apogeeYearMin?.let { DetailRow("À boire à partir de", it.toString()) }
                val apogeeRange = when {
                    e.apogeeYearMin != null && e.apogeeYearMax != null &&
                        e.apogeeYearMin != e.apogeeYearMax ->
                            "${e.apogeeYearMin} – ${e.apogeeYearMax}"
                    e.apogeeYearMax != null && e.apogeeYearMin == null ->
                        "jusqu'à ${e.apogeeYearMax}"
                    e.apogeeYearMin != null && e.apogeeYearMin == e.apogeeYearMax ->
                        e.apogeeYearMin.toString()
                    else -> null
                }
                apogeeRange?.let { DetailRow("Fenêtre d'apogée", it) }
                e.keepingYearMax?.let { DetailRow("À boire avant", it.toString()) }

                e.pairingsIdeal?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(4.dp))
                    Text("Accords idéaux", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        it.split(",").map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                            .forEach { dish ->
                                androidx.compose.material3.SuggestionChip(
                                    onClick = {},
                                    label = { Text(dish) },
                                )
                            }
                    }
                }
                e.pairingsPossible?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(2.dp))
                    Text("Aussi possible", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        it.split(",").map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                            .forEach { dish ->
                                androidx.compose.material3.SuggestionChip(
                                    onClick = {},
                                    label = { Text(dish) },
                                )
                            }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenBottle,
                    modifier = Modifier.weight(1f),
                    enabled = (bouteilles ?: 0) > 0,
                ) { Text("Je débouche") }
                androidx.compose.material3.OutlinedButton(
                    onClick = onEnrich,
                    modifier = Modifier.weight(1f),
                    enabled = !enriching,
                ) {
                    Text(
                        when {
                            enriching -> "Analyse…"
                            enrichment?.enrichmentSummary.isNullOrBlank() -> "Analyser"
                            else -> "Réanalyser"
                        },
                    )
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
