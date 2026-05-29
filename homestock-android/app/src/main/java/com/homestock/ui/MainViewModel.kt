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
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
            // Only react when fields that actually matter for sync/notif change,
            // otherwise unrelated edits (renaming a user, switching profile…)
            // would re-trigger a full refresh and a duplicate notification.
            settingsRepository.settings
                .distinctUntilChangedBy {
                    listOf(it.nasHost, it.nasPort, it.notificationsEnabled, it.setupCompleted)
                }
                .collect { s ->
                    repository.updateNas(s.nasHost, s.nasPort)
                    if (s.setupCompleted) {
                        runCatching { repository.refreshAll() }
                        if (s.notificationsEnabled) {
                            runCatching {
                                expirationNotifier.notify(repository.expiringWithin(3))
                            }
                        }
                    }
                }
        }
    }

    fun retrySync() {
        viewModelScope.launch { runCatching { repository.refreshAll() } }
    }
}
