package com.homestock.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.domain.model.SearchResult
import com.homestock.voice.ParsedCommand
import com.homestock.voice.VoiceParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VoiceIntent {
    data class Search(val query: String) : VoiceIntent
    data class Add(val command: ParsedCommand) : VoiceIntent
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val parser = VoiceParser()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val voiceLanguage: StateFlow<String> = settingsRepository.settings
        .map { it.voiceLanguage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fr-FR")

    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .map { it.filter(ZoneEntity::actif) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent: StateFlow<List<ObjetEntity>> = repository.observeRecent(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiringSoon: StateFlow<List<ObjetEntity>> = repository.observeExpiringSoon(7)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
        if (value.isBlank()) _results.value = emptyList()
    }

    fun search(value: String = _query.value) {
        _query.value = value
        if (value.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searching.value = true
            _results.value = repository.search(value)
            _searching.value = false
        }
    }

    /** Decide whether a spoken phrase is a search or an "add" command. */
    fun classifyVoice(text: String): VoiceIntent {
        val lower = text.lowercase()
        val isAdd = listOf("range", "ranger", "mets", "ajoute", "place").any {
            lower.startsWith(it)
        }
        return if (isAdd) {
            VoiceIntent.Add(parser.parseAddCommand(text, zones.value))
        } else {
            VoiceIntent.Search(text)
        }
    }

    fun photoUrl(relative: String?): String? = repository.absolutePhotoUrl(relative)
}
