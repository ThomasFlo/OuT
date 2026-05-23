package com.homestock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.repository.AppSettings
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import com.homestock.notifications.ExpirationNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
    private val expirationNotifier: ExpirationNotifier,
) : ViewModel() {

    val connected: StateFlow<Boolean> = repository.connected

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                repository.updateNas(s.nasHost, s.nasPort)
                if (s.setupCompleted) {
                    runCatching { repository.refreshAll() }
                    if (s.notificationsEnabled) {
                        runCatching { expirationNotifier.notify(repository.expiringWithin(3)) }
                    }
                }
            }
        }
    }

    fun retrySync() {
        viewModelScope.launch { runCatching { repository.refreshAll() } }
    }
}
