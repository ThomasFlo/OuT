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

/** In-progress emplacement being created (id == null) or edited. */
data class EmplacementDraft(
    val id: Long? = null,
    val nom: String = "",
    val description: String = "",
    val photoUrl: String? = null,
    val uploading: Boolean = false,
)

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

    // Draft for the create/edit emplacement dialog. Hoisted into the VM so
    // the in-progress name / description / photo survive the full-screen
    // camera round-trip (which tears down the dialog composable).
    private val _draft = MutableStateFlow<EmplacementDraft?>(null)
    val draft: StateFlow<EmplacementDraft?> = _draft.asStateFlow()

    init {
        viewModelScope.launch { repository.getZone(zoneId)?.let { _zoneNom.value = it.nom } }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)

    fun clearMessage() { _message.value = null }

    // ----- Emplacement create / edit draft -----

    fun startCreateEmplacement() { _draft.value = EmplacementDraft() }

    fun startEditEmplacement(emp: EmplacementEntity) {
        _draft.value = EmplacementDraft(
            id = emp.id,
            nom = emp.nomEmplacement,
            description = emp.description.orEmpty(),
            photoUrl = emp.photoUrl,
        )
    }

    fun cancelDraft() { _draft.value = null }

    fun updateDraft(transform: (EmplacementDraft) -> EmplacementDraft) {
        _draft.value = _draft.value?.let(transform)
    }

    fun uploadDraftPhoto(bytes: ByteArray) {
        val current = _draft.value ?: return
        _draft.value = current.copy(uploading = true)
        viewModelScope.launch {
            val url = repository.uploadPhoto(bytes)
            // Persist the relative path (strip the base URL) for portability,
            // matching how object photos are stored.
            val relative = url?.substringAfter("/photos/")?.let { "/photos/$it" } ?: url
            _draft.value = _draft.value?.copy(photoUrl = relative, uploading = false)
        }
    }

    fun removeDraftPhoto() { updateDraft { it.copy(photoUrl = null) } }

    fun saveDraft() {
        val d = _draft.value ?: return
        val nom = d.nom.trim()
        if (nom.isBlank()) { _message.value = "Le nom est requis"; return }
        viewModelScope.launch {
            val result = if (d.id == null) {
                runCatching {
                    repository.createEmplacement(
                        zoneId = zoneId,
                        nom = nom,
                        description = d.description.ifBlank { null },
                        photoUrl = d.photoUrl,
                    )
                    "Emplacement créé"
                }
            } else {
                runCatching {
                    repository.updateEmplacement(
                        EmplacementEntity(
                            id = d.id,
                            zoneId = zoneId,
                            nomEmplacement = nom,
                            description = d.description.ifBlank { null },
                            photoUrl = d.photoUrl,
                        ),
                    )
                    "Emplacement mis à jour"
                }
            }
            result.onSuccess { _message.value = it; _draft.value = null }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    // ----- Emplacement deletion -----

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
