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
import kotlinx.coroutines.flow.asStateFlow
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repository.getZone(zoneId)?.let { _zoneNom.value = it.nom } }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)

    fun clearMessage() { _message.value = null }

    // ----- Emplacement management -----

    fun addEmplacement(nom: String) {
        if (nom.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.createEmplacement(zoneId, nom.trim(), description = null, photoUrl = null)
            }.onSuccess { _message.value = "Emplacement créé" }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun renameEmplacement(emp: EmplacementEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == emp.nomEmplacement) return
        viewModelScope.launch {
            runCatching { repository.updateEmplacement(emp.copy(nomEmplacement = trimmed)) }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun deleteEmplacement(emp: EmplacementEntity) {
        viewModelScope.launch {
            runCatching { repository.deleteEmplacement(emp.id) }
                .onSuccess { _message.value = "Emplacement supprimé" }
                .onFailure { _message.value = "Suppression refusée : ${it.message}" }
        }
    }

    fun migrateAndDeleteEmplacement(source: EmplacementEntity, targetEmpId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.migrateEmplacement(source.id, targetEmpId, deleteSource = true)
            }.onSuccess { _message.value = "Emplacement supprimé, contenu transféré" }
                .onFailure { _message.value = "Migration échouée : ${it.message}" }
        }
    }

    /** Local count of objets attached to [empId], for dialog wording. */
    suspend fun objetsCount(empId: Long): Int = repository.countObjetsForEmplacement(empId)
}
