package com.homestock.ui.screens.wine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.remote.dto.WineStats
import com.homestock.data.repository.HomeStockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WineViewModel @Inject constructor(
    private val repository: HomeStockRepository,
) : ViewModel() {

    val wines: StateFlow<List<ObjetEntity>> = repository.observeWines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _stats = MutableStateFlow<WineStats?>(null)
    val stats: StateFlow<WineStats?> = _stats.asStateFlow()

    private val _typeFilter = MutableStateFlow<String?>(null)
    val typeFilter: StateFlow<String?> = _typeFilter.asStateFlow()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch { _stats.value = repository.wineStats() }
    }

    fun setTypeFilter(type: String?) {
        _typeFilter.value = type
    }

    fun openBottle(objet: ObjetEntity) {
        val serverId = objet.serverId ?: return
        viewModelScope.launch {
            runCatching { repository.openBottle(serverId) }
            refreshStats()
        }
    }
}
