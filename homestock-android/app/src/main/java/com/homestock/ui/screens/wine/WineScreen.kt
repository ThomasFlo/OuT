package com.homestock.ui.screens.wine

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.domain.model.WineTypes
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.rememberConfirmHaptic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineScreen(
    onBack: () -> Unit,
    onObjet: (Long) -> Unit = {},
    onAddBottle: () -> Unit = {},
    connected: Boolean = true,
    viewModel: WineViewModel = hiltViewModel(),
) {
    val wines by viewModel.wines.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val priority by viewModel.priority.collectAsStateWithLifecycle()
    val confirmHaptic = rememberConfirmHaptic()

    val filtered = wines.filter { typeFilter == null || it.vinType == typeFilter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cave à vins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = { ConnectionDot(connected, Modifier.padding(end = 12.dp)) },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onAddBottle,
                icon = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("Ajouter une bouteille") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (priority.isNotEmpty()) {
                item {
                    Text(
                        "Prochaines bouteilles à déboucher",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(priority) { p ->
                    PriorityCard(p, onClick = {
                        // The list uses local objet localIds for routing; we
                        // need to find the matching wine in the cached list
                        // (server returns objet_id, ObjetEntity has localId).
                        wines.firstOrNull { it.serverId == p.objetId }?.let { onObjet(it.localId) }
                    })
                }
            }
            item {
                stats?.let { s ->
                    Card(
                        Modifier.fillMaxWidth().padding(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "${s.totalBouteilles} bouteilles",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            s.parType.forEach { (type, n) ->
                                Text("$type : $n", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    FilterChip(
                        selected = typeFilter == null,
                        onClick = { viewModel.setTypeFilter(null) },
                        label = { Text("Tous") },
                    )
                    Spacer(Modifier.width(8.dp))
                    WineTypes.ALL.forEach { type ->
                        FilterChip(
                            selected = typeFilter == type,
                            onClick = { viewModel.setTypeFilter(type) },
                            label = { Text(type) },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            items(filtered) { wine ->
                Card(
                    onClick = { onObjet(wine.localId) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            wine.vinAppellation ?: wine.nom,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                wine.vinDomaine?.let { append("$it ") }
                                wine.vinMillesime?.let { append("• $it ") }
                                wine.vinType?.let { append("• $it") }
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${wine.vinNombreBouteilles ?: 0} bouteille(s)")
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmHaptic(); viewModel.openBottle(wine) }) {
                                Text("Déboucher")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityCard(
    p: com.homestock.data.remote.dto.WinePriorityDto,
    onClick: () -> Unit,
) {
    // Colour-coding follows the urgency tiers from /vins/priority:
    //   past_limit  → red (too late)         past_peak → orange (out of window)
    //   near_limit  → tertiary (closing)     at_peak   → green (drink now, perfect)
    val accent = when (p.urgency) {
        "past_limit" -> MaterialTheme.colorScheme.errorContainer
        "past_peak" -> MaterialTheme.colorScheme.tertiaryContainer
        "at_peak" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onAccent = when (p.urgency) {
        "past_limit" -> MaterialTheme.colorScheme.onErrorContainer
        "past_peak" -> MaterialTheme.colorScheme.onTertiaryContainer
        "at_peak" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = accent),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                p.appellation ?: p.nom,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = onAccent,
            )
            val subtitle = buildString {
                p.domaine?.let { append("$it ") }
                p.millesime?.let { append("($it) ") }
                p.type?.let { append("• $it") }
            }.trim()
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = onAccent)
            }
            Text(
                p.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = onAccent,
                modifier = Modifier.padding(top = 4.dp),
            )
            p.nombreBouteilles?.let {
                Text(
                    "$it bouteille${if (it > 1) "s" else ""} restante${if (it > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onAccent,
                )
            }
        }
    }
}
