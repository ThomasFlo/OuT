package com.homestock.ui.screens.add

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
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
import java.net.URLDecoder
import javax.inject.Inject

data class AddFormState(
    val step: Int = 0,
    val nom: String = "",
    val categorie: String = "Autre",
    val sousCategorie: String = "",
    val photoObjetUrl: String? = null,
    val zoneId: Long? = null,
    val emplacementId: Long? = null,
    val newEmplacementNom: String = "",
    val photoEmplacementUrl: String? = null,
    val quantite: String = "",
    val unite: String = "",
    val etat: String? = null,
    val dateExpiration: Long? = null,
    val notes: String = "",
    // Wine
    val vinAppellation: String = "",
    val vinDomaine: String = "",
    val vinMillesime: String = "",
    val vinType: String = "",
    val vinNombreBouteilles: String = "",
    val vinEmplacementRangee: String = "",
    val vinNotesDegustation: String = "",
    val vinPrixAchat: String = "",
    val vinABoirePartir: String = "",
    val saving: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddObjetViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(AddFormState())
    val state: StateFlow<AddFormState> = _state.asStateFlow()

    val categories: StateFlow<List<String>> = repository.categories

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

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { currentUser = it.currentUser }
        }
        // Apply voice prefill if present.
        fun decode(key: String) = savedStateHandle.get<String>(key)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val nom = decode("nom")
        val zoneId = savedStateHandle.get<String>("zoneId")?.toLongOrNull()
        val emp = decode("emp")
        val qty = savedStateHandle.get<String>("qty")
        if (nom != null || zoneId != null || emp != null) {
            _state.update {
                it.copy(
                    nom = nom ?: it.nom,
                    zoneId = zoneId,
                    newEmplacementNom = emp ?: it.newEmplacementNom,
                    quantite = qty ?: it.quantite,
                )
            }
            selectedZone.value = zoneId
        }
    }

    fun update(transform: (AddFormState) -> AddFormState) = _state.update(transform)

    fun selectZone(zoneId: Long) {
        selectedZone.value = zoneId
        _state.update { it.copy(zoneId = zoneId, emplacementId = null) }
    }

    fun nextStep() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(3)) }
    fun prevStep() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun uploadObjetPhoto(bytes: ByteArray) = upload(bytes) { url ->
        _state.update { it.copy(photoObjetUrl = url) }
    }

    fun uploadEmplacementPhoto(bytes: ByteArray) = upload(bytes) { url ->
        _state.update { it.copy(photoEmplacementUrl = url) }
    }

    private fun upload(bytes: ByteArray, onUrl: (String?) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
            val url = repository.uploadPhoto(bytes)
            // Store the relative path (strip the base URL) for portability.
            onUrl(url?.substringAfter("/photos/")?.let { "/photos/$it" } ?: url)
            _state.update { it.copy(uploading = false) }
        }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.nom.isBlank() || s.zoneId == null ||
            (s.emplacementId == null && s.newEmplacementNom.isBlank())
        ) {
            _state.update { it.copy(error = "Nom, zone et emplacement sont requis") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val result = runCatching {
                val emplacementId = s.emplacementId ?: repository.createEmplacement(
                    zoneId = s.zoneId,
                    nom = s.newEmplacementNom,
                    description = null,
                    photoUrl = s.photoEmplacementUrl,
                ).id
                repository.saveObjet(buildEntity(s, emplacementId))
            }
            result
                .onSuccess { _state.update { it.copy(saving = false) }; onDone() }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, error = e.message ?: "Erreur") }
                }
        }
    }

    private fun buildEntity(s: AddFormState, emplacementId: Long) = ObjetEntity(
        nom = s.nom,
        emplacementId = emplacementId,
        categorie = s.categorie,
        sousCategorie = s.sousCategorie.ifBlank { null },
        quantite = s.quantite.toIntOrNull(),
        unite = s.unite.ifBlank { null },
        etat = s.etat,
        dateExpiration = s.dateExpiration,
        photoUrl = s.photoObjetUrl,
        notes = s.notes.ifBlank { null },
        ajoutePar = currentUser,
        vinAppellation = s.vinAppellation.ifBlank { null },
        vinDomaine = s.vinDomaine.ifBlank { null },
        vinMillesime = s.vinMillesime.toIntOrNull(),
        vinType = s.vinType.ifBlank { null },
        vinNombreBouteilles = s.vinNombreBouteilles.toIntOrNull(),
        vinEmplacementRangee = s.vinEmplacementRangee.ifBlank { null },
        vinNotesDegustation = s.vinNotesDegustation.ifBlank { null },
        vinPrixAchat = s.vinPrixAchat.toDoubleOrNull(),
        vinABoirePartir = s.vinABoirePartir.toIntOrNull(),
    )
}
