package com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.model.BiometricsKeyResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Cipher

class BiometricEnrollViewModelTest {
    private val userState: UserState = mockk { every { activeUserId } returns USER_ID }
    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns MutableStateFlow(userState)
        every { createCipherOrNull(USER_ID) } returns CIPHER
    }
    private val settingsRepository: SettingsRepository = mockk {
        every { isUnlockWithBiometricsEnabled } returns false
        coEvery { setupBiometricsKey(CIPHER) } returns BiometricsKeyResult.Success
    }

    private fun createViewModel() = BiometricEnrollViewModel(
        authRepository = authRepository,
        settingsRepository = settingsRepository,
        dispatcherManager = FakeDispatcherManager(),
    )

    @Test
    fun `after the first unlock a Keystore cipher is offered`() {
        val viewModel = createViewModel()
        assertTrue(viewModel.isEnrollmentNeeded())
        assertEquals(CIPHER, viewModel.cipherIfEnrollmentNeeded())
    }

    @Test
    fun `enroll wraps the user key through the same call as the Settings toggle`() {
        createViewModel().enroll(CIPHER)
        coVerify(exactly = 1) { settingsRepository.setupBiometricsKey(CIPHER) }
    }

    @Test
    fun `nothing is offered once biometric unlock is enabled`() {
        every { settingsRepository.isUnlockWithBiometricsEnabled } returns true
        val viewModel = createViewModel()
        assertFalse(viewModel.isEnrollmentNeeded())
        assertNull(viewModel.cipherIfEnrollmentNeeded())
        verify(exactly = 0) { authRepository.createCipherOrNull(any()) }
    }
}

private const val USER_ID = "mockUserId"
private val CIPHER = mockk<Cipher>()
