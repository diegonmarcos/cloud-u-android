package com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar

import androidx.lifecycle.ViewModel
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Offline-first local lock: the vault is opened by the device biometric, not by the server. After the first
 * master-password unlock the vault key is wrapped by a Keystore key that requires biometric auth (and is
 * invalidated by a new enrolment, where the master password is the fallback). Enrolment is offered once.
 */
@HiltViewModel
class BiometricEnrollViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    dispatcherManager: DispatcherManager,
) : ViewModel() {
    // Not the composition's or the view model's scope: leaving the screen right after the fingerprint must not drop the wrap.
    private val enrollScope = CoroutineScope(SupervisorJob() + dispatcherManager.io)

    /** True when a master-password unlock should be followed by the biometric wrap. */
    fun isEnrollmentNeeded(): Boolean =
        authRepository.userStateFlow.value?.activeUserId != null &&
            !settingsRepository.isUnlockWithBiometricsEnabled

    /** A fresh Keystore-backed cipher when biometrics are not wrapped yet, else null. */
    fun cipherIfEnrollmentNeeded(): Cipher? {
        val userId = authRepository.userStateFlow.value?.activeUserId ?: return null
        if (settingsRepository.isUnlockWithBiometricsEnabled) return null
        return authRepository.createCipherOrNull(userId = userId)
    }

    /** Wraps the user key with the biometric Keystore key, the same call as the Settings toggle. */
    fun enroll(cipher: Cipher) {
        enrollScope.launch {
            val result = settingsRepository.setupBiometricsKey(cipher = cipher)
            Timber.i("Biometric auto-enrol result: %s", result)
        }
    }
}
