package com.homestock.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _testResult = MutableStateFlow<Boolean?>(null)
    val testResult: StateFlow<Boolean?> = _testResult.asStateFlow()

    fun testConnection(host: String, port: Int) {
        viewModelScope.launch {
            repository.updateNas(host, port)
            _testResult.value = repository.testConnection()
        }
    }

    fun completeSetup(
        host: String,
        port: Int,
        user1: String,
        user2: String,
        currentUser: String,
    ) {
        viewModelScope.launch {
            settingsRepository.setNas(host, port)
            settingsRepository.setUsers(user1, user2)
            settingsRepository.setCurrentUser(currentUser)
            repository.updateNas(host, port)
            runCatching { repository.refreshAll() }
            settingsRepository.setSetupCompleted(true)
        }
    }
}
