package com.homestock.ui.screens.wine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.domain.model.Categories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Form state for the dedicated "add a bottle" flow.
 *
 * Kept deliberately flat — wine entry through the generic 4-step objet
 * wizard was overkill: 90 % of the fields (sous-catégorie, état, expiry)
 * never apply to wine. This screen drives a single page with just the
 * wine-relevant inputs and skips straight to saveObjet.
 */
data class AddWineState(
    val nom: String = "",
    val appellation: String = "",
    val domaine: String = "",
    val millesime: String = "",
    val type: String = "Rouge",
    val nombreBouteilles: String = "1",
    val photoUrl: String? = null,
    val zoneId: Long? = null,
    val emplacementId: Long? = null,
    val newEmplacementNom: String = "",
    val notes: String = "",
    val saving: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddWineViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(AddWineState())
    val state: StateFlow<AddWineState> = _state.asStateFlow()

    // Only show active zones — deactivated zones in Settings shouldn't
    // surface in the picker for new content.
    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .map { it.filter(ZoneEntity::actif) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedZone = MutableStateFlow<Long?>(null)
    val emplacements: StateFlow<List<EmplacementEntity>> = selectedZone
        .flatMapLatest { zoneId ->
            if (zoneId == null) flowOf(emptyList()) else repository.observeEmplacements(zoneId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var currentUser: String = "Utilisateur 1"
    private val saveInFlight = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { currentUser = it.currentUser }
        }
        // If launched from a specific zone (long-pressed a zone card, future
        // use), preselect it.
        savedStateHandle.get<String>("zoneId")?.toLongOrNull()?.let { preset ->
            selectedZone.value = preset
            _state.update { it.copy(zoneId = preset) }
        }
    }

    fun update(transform: (AddWineState) -> AddWineState) = _state.update(transform)

    fun selectZone(zoneId: Long) {
        selectedZone.value = zoneId
        _state.update { it.copy(zoneId = zoneId, emplacementId = null) }
    }

    fun selectEmplacement(empId: Long?) {
        _state.update { it.copy(emplacementId = empId) }
    }

    fun uploadPhoto(bytes: ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            val url = repository.uploadPhoto(bytes)
            val relative = url?.substringAfter("/photos/")?.let { "/photos/$it" } ?: url
            _state.update { it.copy(photoUrl = relative, uploading = false) }
        }
    }

    fun clearPhoto() = _state.update { it.copy(photoUrl = null) }

    /**
     * Persist the bottle. Mirrors AddObjetViewModel.save's guard but with a
     * wine-specific entity builder: categorie is forced to Categories.WINE
     * and the bottle count drives both objet.quantite and vin.nombreBouteilles.
     */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.appellation.isBlank() && s.nom.isBlank()) {
            _state.update { it.copy(error = "Une appellation ou un nom est requis") }
            return
        }
        if (s.zoneId == null || (s.emplacementId == null && s.newEmplacementNom.isBlank())) {
            _state.update { it.copy(error = "Zone et emplacement sont requis") }
            return
        }
        if (!saveInFlight.compareAndSet(false, true)) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val result = runCatching {
                    val empId = s.emplacementId ?: repository.createEmplacement(
                        zoneId = s.zoneId,
                        nom = s.newEmplacementNom,
                        description = null,
                        photoUrl = null,
                    ).id
                    repository.saveObjet(buildEntity(s, empId))
                }
                result
                    .onSuccess { _state.update { it.copy(saving = false) }; onDone() }
                    .onFailure { e ->
                        _state.update { it.copy(saving = false, error = e.message ?: "Erreur") }
                    }
            } finally {
                saveInFlight.set(false)
            }
        }
    }

    private fun buildEntity(s: AddWineState, empId: Long): ObjetEntity {
        val bottles = s.nombreBouteilles.toIntOrNull() ?: 1
        // Build a meaningful "nom" if the user only filled in appellation:
        // the listing screens show appellation by preference but the objet
        // row always needs a non-null name.
        val nom = s.nom.ifBlank {
            buildString {
                append(s.appellation.ifBlank { "Bouteille" })
                s.millesime.toIntOrNull()?.let { append(" $it") }
            }
        }
        return ObjetEntity(
            localId = 0,
            serverId = null,
            dateAjout = System.currentTimeMillis(),
            nom = nom,
            emplacementId = empId,
            categorie = Categories.WINE,
            sousCategorie = null,
            quantite = bottles,
            unite = "bouteille",
            etat = null,
            dateExpiration = null,
            photoUrl = s.photoUrl,
            notes = s.notes.ifBlank { null },
            ajoutePar = currentUser,
            vinAppellation = s.appellation.ifBlank { null },
            vinDomaine = s.domaine.ifBlank { null },
            vinMillesime = s.millesime.toIntOrNull(),
            vinType = s.type.ifBlank { null },
            vinNombreBouteilles = bottles,
            vinEmplacementRangee = null,
            vinNotesDegustation = null,
            vinPrixAchat = null,
            vinABoirePartir = null,
        )
    }
}
