package com.homestock.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.repository.HomeStockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        }
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
