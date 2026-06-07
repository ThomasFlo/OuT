package com.homestock.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.remote.apiErrorMessage
import com.homestock.data.remote.dto.VinDto
import com.homestock.data.repository.HomeStockRepository
import com.homestock.domain.model.Categories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ObjetDetailState(
    val objet: ObjetEntity? = null,
    val zoneId: Long? = null,
    val zoneNom: String? = null,
    val zoneIcone: String? = null,
    val zoneCouleur: String? = null,
    val emplacementNom: String? = null,
    val emplacementPhotoUrl: String? = null,
    val emplacementDescription: String? = null,
    // Wine enrichment is fetched from the server on demand (not cached locally
    // in Room) so the rest of the app stays unaware of it.
    val vinEnrichment: VinDto? = null,
    val enriching: Boolean = false,
    /** Partial sommelier text growing as Llama generates — null while idle. */
    val streamingSummary: String? = null,
    val enrichmentError: String? = null,
)

@HiltViewModel
class ObjetDetailViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val localId: Long = savedStateHandle.get<String>("localId")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(ObjetDetailState())
    val state: StateFlow<ObjetDetailState> = _state.asStateFlow()

    // Atomic re-entry guards: the delete/openBottle buttons can be tapped twice
    // before the UI has a chance to disable them.
    private val deleteInFlight = AtomicBoolean(false)
    private val openBottleInFlight = AtomicBoolean(false)

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val objet = repository.getObjet(localId) ?: return@launch
            val emp = repository.getEmplacement(objet.emplacementId)
            val zone = emp?.let { repository.getZone(it.zoneId) }
            _state.value = ObjetDetailState(
                objet = objet,
                zoneId = zone?.id,
                zoneNom = zone?.nom,
                zoneIcone = zone?.icone,
                zoneCouleur = zone?.couleur,
                emplacementNom = emp?.nomEmplacement,
                emplacementPhotoUrl = emp?.photoUrl,
                emplacementDescription = emp?.description,
            )
            // For wines, pull the enrichment from the server. The local
            // Room cache doesn't carry these columns so we always hit the
            // network; failure is silent (the fiche just doesn't show the
            // extra panel).
            if (objet.categorie == Categories.WINE && objet.serverId != null) {
                val vin = repository.fetchVinDto(objet.serverId)
                if (vin != null) _state.update { it.copy(vinEnrichment = vin) }
            }
        }
    }

    /**
     * Streams the sommelier response from the server. The dialog state
     * carries [streamingSummary] which grows as Llama emits each token,
     * giving the user a ChatGPT-style "typing" effect. The terminal Done
     * event swaps to the final [vinEnrichment] and clears the streaming
     * state; Failed surfaces the message in [enrichmentError].
     */
    fun enrichWine() {
        val obj = _state.value.objet ?: return
        val serverId = obj.serverId ?: return
        if (_state.value.enriching) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    enriching = true,
                    streamingSummary = "",
                    enrichmentError = null,
                )
            }
            try {
                repository.enrichWineStream(serverId).collect { event ->
                    when (event) {
                        com.homestock.data.remote.WineEnrichEvent.Started ->
                            // Already enriching=true, just acknowledge the
                            // connection — useful UX cue so the user sees
                            // something is happening before Llama starts.
                            _state.update { it.copy(streamingSummary = "") }
                        is com.homestock.data.remote.WineEnrichEvent.SummaryDelta ->
                            _state.update { it.copy(streamingSummary = event.text) }
                        is com.homestock.data.remote.WineEnrichEvent.Done ->
                            _state.update {
                                it.copy(
                                    enriching = false,
                                    streamingSummary = null,
                                    vinEnrichment = event.objet.vin
                                        ?: it.vinEnrichment,
                                )
                            }
                        is com.homestock.data.remote.WineEnrichEvent.Failed ->
                            _state.update {
                                it.copy(
                                    enriching = false,
                                    streamingSummary = null,
                                    enrichmentError = event.message,
                                )
                            }
                    }
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        enriching = false,
                        streamingSummary = null,
                        enrichmentError = apiErrorMessage(e)
                            ?: "Échec de l'enrichissement",
                    )
                }
            }
        }
    }

    fun clearEnrichmentError() {
        _state.update { it.copy(enrichmentError = null) }
    }

    fun delete(onDone: () -> Unit) {
        val objet = _state.value.objet ?: return
        if (!deleteInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                repository.deleteObjet(objet)
                onDone()
            } finally {
                deleteInFlight.set(false)
            }
        }
    }

    fun openBottle() {
        val serverId = _state.value.objet?.serverId ?: return
        if (!openBottleInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                runCatching { repository.openBottle(serverId) }
                load()
            } finally {
                openBottleInFlight.set(false)
            }
        }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)
}
