package com.homestock.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.homestock.data.local.ZoneEntity
import com.homestock.data.remote.dto.AppVersionDto
import com.homestock.data.remote.dto.CategoryDto
import com.homestock.data.repository.AppSettings
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.update.UpdateManager
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
    private val updateManager: UpdateManager,
) : ViewModel() {

    val appVersionName: String = updateManager.currentVersionName
    val appVersionCode: Int = updateManager.currentVersionCode

    private val _serverVersion = MutableStateFlow<AppVersionDto?>(null)
    val serverVersion: StateFlow<AppVersionDto?> = _serverVersion.asStateFlow()

    init {
        refreshServerVersion()
        viewModelScope.launch { runCatching { repository.refreshCategories() } }
    }

    fun refreshServerVersion() {
        viewModelScope.launch { _serverVersion.value = updateManager.fetchServerVersion() }
    }

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val zones: StateFlow<List<ZoneEntity>> = repository.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryDto>> = repository.categoriesDetailed
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

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDebugMode(enabled) }
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
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    /** Update a zone's name, icon and colour in one shot (from the edit dialog). */
    fun updateZoneDetails(zone: ZoneEntity, nom: String, icone: String, couleur: String) {
        val trimmed = nom.trim().ifBlank { zone.nom }
        viewModelScope.launch {
            runCatching {
                repository.updateZone(zone.copy(nom = trimmed, icone = icone, couleur = couleur))
            }.onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun toggleZone(zone: ZoneEntity) {
        viewModelScope.launch {
            runCatching { repository.updateZone(zone.copy(actif = !zone.actif)) }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun deleteZone(zone: ZoneEntity) {
        viewModelScope.launch {
            runCatching { repository.deleteZone(zone.id) }
                .onSuccess { _message.value = "Zone supprimée" }
                .onFailure { _message.value = "Suppression refusée : ${it.message}" }
        }
    }

    /** Count of emplacements still attached to [zoneId] in the local cache. */
    suspend fun emplacementsCount(zoneId: Long): Int = repository.countEmplacements(zoneId)

    /**
     * Moves the source zone's emplacements onto [targetZoneId] then deletes
     * the source in one server-side transaction.
     */
    fun migrateAndDeleteZone(source: ZoneEntity, targetZoneId: Long) {
        viewModelScope.launch {
            runCatching { repository.migrateZone(source.id, targetZoneId, deleteSource = true) }
                .onSuccess { _message.value = "Zone supprimée, contenu transféré" }
                .onFailure { _message.value = "Migration échouée : ${it.message}" }
        }
    }

    fun reorderZones(orderedIds: List<Long>) {
        viewModelScope.launch {
            runCatching { repository.reorderZones(orderedIds) }
                .onFailure { _message.value = "Réordonnancement échoué : ${it.message}" }
        }
    }

    // ----- Category management -----

    fun addCategory(nom: String) {
        if (nom.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.createCategory(nom.trim()) }
                .onSuccess { _message.value = "Catégorie créée" }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun renameCategory(category: CategoryDto, newNom: String) {
        val trimmed = newNom.trim()
        if (trimmed.isBlank() || trimmed == category.nom) return
        viewModelScope.launch {
            runCatching { repository.renameCategory(category.id, trimmed) }
                .onFailure { _message.value = "Échec : ${it.message}" }
        }
    }

    fun deleteCategory(category: CategoryDto) {
        viewModelScope.launch {
            runCatching { repository.deleteCategory(category.id) }
                .onSuccess { _message.value = "Catégorie supprimée" }
                .onFailure { _message.value = "Suppression refusée : ${it.message}" }
        }
    }

    fun migrateAndDeleteCategory(source: CategoryDto, targetCategoryId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.migrateCategory(source.id, targetCategoryId, deleteSource = true)
            }
                .onSuccess { _message.value = "Catégorie supprimée, objets réaffectés" }
                .onFailure { _message.value = "Migration échouée : ${it.message}" }
        }
    }

    fun reorderCategories(orderedIds: List<Long>) {
        viewModelScope.launch {
            runCatching { repository.reorderCategories(orderedIds) }
                .onFailure { _message.value = "Réordonnancement échoué : ${it.message}" }
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
