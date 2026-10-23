package com.x8bit.bitwarden.fleet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.platform.manager.network.NetworkConnectionManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Drives the sync banner: the offline / last-synced line and the non-destructive "Sign in" action. */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
    networkConnectionManager: NetworkConnectionManager,
) : ViewModel() {
    val offlineLabel: StateFlow<String?> = combine(
        networkConnectionManager.isNetworkConnectedFlow,
        SyncHealth.lastSyncFailed,
        settingsRepository.vaultLastSyncStateFlow,
    ) { connected, failed, lastSync -> OfflineStatus.label(connected, failed, lastSync) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableError = MutableStateFlow(false)
    val signInFailed: StateFlow<Boolean> = mutableError

    /** Refreshes only the tokens; on success the banner clears, on failure the vault is untouched. */
    fun signIn(password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val ok = authRepository.reauthenticate(password).isSuccess
            mutableError.value = !ok
            if (ok) onDone()
        }
    }

    fun clearError() {
        mutableError.value = false
    }
}
