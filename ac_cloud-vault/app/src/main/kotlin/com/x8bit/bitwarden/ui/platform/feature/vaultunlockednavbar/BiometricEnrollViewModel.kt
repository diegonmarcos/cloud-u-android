package com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar

import androidx.lifecycle.ViewModel
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {
    /** A fresh Keystore-backed cipher when biometrics are not wrapped yet, else null. */
    fun cipherIfEnrollmentNeeded(): Cipher? {
        val userId = authRepository.userStateFlow.value?.activeUserId ?: return null
        if (settingsRepository.isUnlockWithBiometricsEnabled) return null
        return authRepository.createCipherOrNull(userId = userId)
    }

    suspend fun enroll(cipher: Cipher) {
        settingsRepository.setupBiometricsKey(cipher = cipher)
    }
}
