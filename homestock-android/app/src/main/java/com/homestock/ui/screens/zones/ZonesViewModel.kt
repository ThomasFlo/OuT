package com.homestock.ui.screens.zones

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ZonesViewModel @Inject constructor(
    private val repository: HomeStockRepository,
) : ViewModel() {

    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .map { it.filter(ZoneEntity::actif) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@HiltViewModel
class ZoneDetailViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val zoneId: Long = savedStateHandle.get<String>("zoneId")?.toLongOrNull() ?: -1L

    val objets: StateFlow<List<ObjetEntity>> = repository.observeObjetsByZone(zoneId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emplacements: StateFlow<List<EmplacementEntity>> = repository.observeEmplacements(zoneId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _zoneNom = MutableStateFlow("Zone")
    val zoneNom: StateFlow<String> = _zoneNom

    init {
        viewModelScope.launch { repository.getZone(zoneId)?.let { _zoneNom.value = it.nom } }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)
}
