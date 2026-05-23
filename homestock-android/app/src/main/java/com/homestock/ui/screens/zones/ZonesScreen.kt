package com.homestock.ui.screens.zones

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homestock.ui.components.ConnectionDot
import com.homestock.ui.components.ZoneCard

@Composable
fun ZonesScreen(
    connected: Boolean,
    onZone: (Long) -> Unit,
    onWine: () -> Unit,
    viewModel: ZonesViewModel = hiltViewModel(),
) {
    val zones by viewModel.zones.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Zones", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            ConnectionDot(connected)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            item {
                OutlinedButton(
                    onClick = onWine,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    Icon(Icons.Filled.WineBar, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Cave à vins")
                }
            }
            items(zones) { z ->
                ZoneCard(z.nom, z.couleur, z.nbObjets, onClick = { onZone(z.id) })
            }
        }
    }
}
