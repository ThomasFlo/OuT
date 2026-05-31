package com.homestock.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homestock.data.remote.dto.AppVersionDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * State machine driving the update dialog shown at app start.
 *
 * Idle → (server has newer version) → Available
 * Available → Downloading → ReadyToInstall (intent fires installer)
 * Any → Error (user can retry or dismiss)
 * User-dismissed states return to Idle.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val info: AppVersionDto) : UpdateState
    data class Downloading(val info: AppVersionDto) : UpdateState
    data class ReadyToInstall(val info: AppVersionDto, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionName: String get() = manager.currentVersionName
    val currentVersionCode: Int get() = manager.currentVersionCode

    /** Run once at app start. Silent if no update or the server is down. */
    fun checkOnce() {
        if (_state.value !is UpdateState.Idle) return
        viewModelScope.launch {
            manager.checkForUpdate()?.let { _state.value = UpdateState.Available(it) }
        }
    }

    fun download(info: AppVersionDto) {
        viewModelScope.launch {
            _state.value = UpdateState.Downloading(info)
            runCatching { manager.downloadAndVerifyApk(info.sha256) }
                .onSuccess { file -> _state.value = UpdateState.ReadyToInstall(info, file) }
                .onFailure { e ->
                    _state.value = UpdateState.Error(
                        e.message ?: "Téléchargement échoué",
                    )
                }
        }
    }

    /**
     * Hands the verified APK to the OS installer. Caller must have ensured
     * [canInstallPackages] returns true (or routed the user through the
     * settings screen via [openInstallSettings]).
     */
    fun install(file: File) {
        manager.installApk(file)
    }

    fun canInstallPackages(): Boolean = manager.canInstallPackages()
    fun openInstallSettings() = manager.openInstallSettings()

    fun dismiss() { _state.value = UpdateState.Idle }
}
