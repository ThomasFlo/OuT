package com.homestock.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.homestock.data.local.ZoneEntity
import com.homestock.data.repository.AppSettings
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
    private val gson: Gson,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _testResult = MutableStateFlow<Boolean?>(null)
    val testResult: StateFlow<Boolean?> = _testResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun saveNas(host: String, port: Int) {
        viewModelScope.launch {
            settingsRepository.setNas(host, port)
            repository.updateNas(host, port)
        }
    }

    fun testConnection() {
        viewModelScope.launch { _testResult.value = repository.testConnection() }
    }

    fun setCurrentUser(name: String) {
        viewModelScope.launch { settingsRepository.setCurrentUser(name) }
    }

    fun setVoiceLanguage(lang: String) {
        viewModelScope.launch { settingsRepository.setVoiceLanguage(lang) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun addZone(nom: String) {
        viewModelScope.launch {
            runCatching { repository.createZone(nom, "home", "#4A90D9") }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun renameZone(zone: ZoneEntity, newNom: String) {
        viewModelScope.launch {
            runCatching { repository.updateZone(zone.copy(nom = newNom)) }
        }
    }

    fun toggleZone(zone: ZoneEntity) {
        viewModelScope.launch {
            runCatching { repository.updateZone(zone.copy(actif = !zone.actif)) }
        }
    }

    suspend fun exportJson(): String = gson.toJson(repository.export())

    fun importJson(json: String) {
        viewModelScope.launch {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(json, Map::class.java) as Map<String, Any>
                repository.importData(map)
            }
                .onSuccess { _message.value = "Import réussi" }
                .onFailure { _message.value = "Échec de l'import : ${it.message}" }
        }
    }

    fun clearMessage() { _message.value = null }
}
