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
import javax.inject.Inject

data class ObjetDetailState(
    val objet: ObjetEntity? = null,
    val zoneNom: String? = null,
    val emplacementNom: String? = null,
)

@HiltViewModel
class ObjetDetailViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val localId: Long = savedStateHandle.get<String>("localId")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(ObjetDetailState())
    val state: StateFlow<ObjetDetailState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val objet = repository.getObjet(localId) ?: return@launch
            val emp = repository.getEmplacement(objet.emplacementId)
            val zone = emp?.let { repository.getZone(it.zoneId) }
            _state.value = ObjetDetailState(objet, zone?.nom, emp?.nomEmplacement)
        }
    }

    fun delete(onDone: () -> Unit) {
        val objet = _state.value.objet ?: return
        viewModelScope.launch {
            repository.deleteObjet(objet)
            onDone()
        }
    }

    fun openBottle() {
        val serverId = _state.value.objet?.serverId ?: return
        viewModelScope.launch {
            runCatching { repository.openBottle(serverId) }
            load()
        }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)
}
