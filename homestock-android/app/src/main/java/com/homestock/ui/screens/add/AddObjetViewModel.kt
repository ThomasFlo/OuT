package com.homestock.ui.screens.add

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.domain.model.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
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
    val isEditing: Boolean = false,
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

    // Auto-suggest similar existing objects as the name is typed (debounced
    // semantic search). Lets the user reuse an existing category/location.
    private val _suggestions = MutableStateFlow<List<SearchResult>>(emptyList())
    val suggestions: StateFlow<List<SearchResult>> = _suggestions.asStateFlow()

    private var currentUser: String = "Utilisateur 1"
    // Set when editing an existing object: lets us preserve server id and
    // creation date across the save instead of creating a new row.
    private var editingEntity: ObjetEntity? = null

    // Atomic CAS guard against re-entering save() / delete() before the in-flight
    // coroutine has finished. The button's enabled flag already gates the UI, but
    // Compose's recomposition lag opens a ~tens-of-ms window where a fast double-
    // tap can reach the handler twice — and that's exactly what was producing the
    // pairs of consecutive-id rows on the server.
    private val saveInFlight = AtomicBoolean(false)
    private val deleteInFlight = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { currentUser = it.currentUser }
        }
        val editLocalId = savedStateHandle.get<String>("localId")?.toLongOrNull()
        if (editLocalId != null) {
            loadForEdit(editLocalId)
        } else {
            applyVoicePrefill(savedStateHandle)
        }
        observeNameSuggestions()
    }

    @OptIn(FlowPreview::class)
    private fun observeNameSuggestions() {
        viewModelScope.launch {
            _state
                .map { it.nom }
                .distinctUntilChanged()
                .debounce(250)
                .collect { nom ->
                    _suggestions.value = if (nom.trim().length >= 2 && !_state.value.isEditing) {
                        runCatching { repository.search(nom).take(5) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
        }
    }

    /** Reuse an existing object's category & location when picking a suggestion. */
    fun applySuggestion(result: SearchResult) {
        _suggestions.value = emptyList()
        viewModelScope.launch {
            val zoneId = repository.getEmplacement(result.objet.emplacementId)?.zoneId
            selectedZone.value = zoneId
            _state.update {
                it.copy(
                    nom = result.objet.nom,
                    categorie = result.objet.categorie,
                    sousCategorie = result.objet.sousCategorie.orEmpty(),
                    zoneId = zoneId,
                    emplacementId = result.objet.emplacementId,
                )
            }
        }
    }

    private fun loadForEdit(localId: Long) {
        viewModelScope.launch {
            val obj = repository.getObjet(localId) ?: return@launch
            editingEntity = obj
            selectedZone.value = repository.getEmplacement(obj.emplacementId)?.zoneId
            _state.update {
                it.copy(
                    isEditing = true,
                    nom = obj.nom,
                    categorie = obj.categorie,
                    sousCategorie = obj.sousCategorie.orEmpty(),
                    photoObjetUrl = obj.photoUrl,
                    zoneId = selectedZone.value,
                    emplacementId = obj.emplacementId,
                    quantite = obj.quantite?.toString().orEmpty(),
                    unite = obj.unite.orEmpty(),
                    etat = obj.etat,
                    dateExpiration = obj.dateExpiration,
                    notes = obj.notes.orEmpty(),
                    vinAppellation = obj.vinAppellation.orEmpty(),
                    vinDomaine = obj.vinDomaine.orEmpty(),
                    vinMillesime = obj.vinMillesime?.toString().orEmpty(),
                    vinType = obj.vinType.orEmpty(),
                    vinNombreBouteilles = obj.vinNombreBouteilles?.toString().orEmpty(),
                    vinEmplacementRangee = obj.vinEmplacementRangee.orEmpty(),
                    vinNotesDegustation = obj.vinNotesDegustation.orEmpty(),
                    vinPrixAchat = obj.vinPrixAchat?.toString().orEmpty(),
                    vinABoirePartir = obj.vinABoirePartir?.toString().orEmpty(),
                )
            }
        }
    }

    private fun applyVoicePrefill(savedStateHandle: SavedStateHandle) {
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

    fun delete(onDone: () -> Unit) {
        val target = editingEntity ?: return
        // Hard guard against re-entry: confirm-dialog rapid double tap used to
        // fire two DELETE calls in flight.
        if (!deleteInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                runCatching { repository.deleteObjet(target) }
                    .onSuccess { onDone() }
                    .onFailure { e -> _state.update { it.copy(error = e.message ?: "Erreur") } }
            } finally {
                deleteInFlight.set(false)
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.nom.isBlank() || s.zoneId == null ||
            (s.emplacementId == null && s.newEmplacementNom.isBlank())
        ) {
            _state.update { it.copy(error = "Nom, zone et emplacement sont requis") }
            return
        }
        // CAS guard — see saveInFlight declaration above. Without this, a fast
        // double-tap on "Enregistrer" raced past the button's enabled flag and
        // produced two POST /objets calls → two rows with consecutive ids.
        if (!saveInFlight.compareAndSet(false, true)) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
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
            } finally {
                saveInFlight.set(false)
            }
        }
    }

    private fun buildEntity(s: AddFormState, emplacementId: Long): ObjetEntity {
        val base = editingEntity
        return ObjetEntity(
        // Preserve identity when editing so we update instead of inserting.
        localId = base?.localId ?: 0,
        serverId = base?.serverId,
        dateAjout = base?.dateAjout ?: System.currentTimeMillis(),
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
}
