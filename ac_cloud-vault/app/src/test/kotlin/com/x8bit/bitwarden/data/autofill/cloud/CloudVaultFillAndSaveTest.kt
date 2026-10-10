package com.x8bit.bitwarden.data.autofill.cloud

import android.app.assist.AssistStructure
import android.content.IntentSender
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.util.mockBuilder
import com.bitwarden.policies.PolicyType
import com.x8bit.bitwarden.data.autofill.builder.FilledDataBuilderImpl
import com.x8bit.bitwarden.data.autofill.builder.SaveInfoBuilderImpl
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillCipher
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.model.FilledItem
import com.x8bit.bitwarden.data.autofill.parser.AutofillParser
import com.x8bit.bitwarden.data.autofill.processor.AutofillProcessorImpl
import com.x8bit.bitwarden.data.autofill.provider.AutofillCipherProvider
import com.x8bit.bitwarden.data.autofill.util.buildFilledItemOrNull
import com.x8bit.bitwarden.data.autofill.util.createAutofillSavedItemIntentSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Datasets for one-time-code fields, the two-screen save (FLAG_DELAY_SAVE) and the save URI
 * policy. Values are obvious fakes.
 */
class CloudVaultFillAndSaveTest {

    private val site = "https://login.example.com"
    private val fakeCode = "123456"

    private fun data(website: String? = site, text: String? = null) = AutofillView.Data(
        autofillId = mockk<AutofillId>(),
        autofillOptions = emptyList(),
        autofillType = View.AUTOFILL_TYPE_TEXT,
        isFocused = true,
        textValue = text,
        hasPasswordTerms = false,
        website = website,
    )

    private fun login(name: String, totpCode: String?) = AutofillCipher.Login(
        cipherId = name,
        isTotpEnabled = totpCode != null,
        name = name,
        subtitle = "",
        password = "fake-pw",
        username = "someone",
        website = site,
        totpCode = totpCode,
    )

    @BeforeEach
    fun setup() {
        mockkStatic(AutofillValue::forText)
        mockkStatic(AutofillView::buildFilledItemOrNull)
        mockkStatic(::createAutofillSavedItemIntentSender)
        mockkConstructor(SaveInfo.Builder::class)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(AutofillValue::forText)
        unmockkStatic(AutofillView::buildFilledItemOrNull)
        unmockkStatic(::createAutofillSavedItemIntentSender)
        unmockkConstructor(SaveInfo.Builder::class)
    }

    // ── one-time codes ────────────────────────────────────────────────

    @Test
    fun `an OTP field asks for TOTP codes and is filled only by logins that have one`() = runTest {
        val filledCode: FilledItem = mockk()
        val otpView: AutofillView.Login.Totp = mockk {
            every { this@mockk.data } returns data()
            every { buildFilledItemOrNull(fakeCode) } returns filledCode
        }
        val provider: AutofillCipherProvider = mockk {
            coEvery { isVaultLocked() } returns false
            coEvery { getLoginAutofillCiphers(uri = site, includeTotpCode = true) } returns listOf(
                login("with-totp", totpCode = fakeCode),
                login("without-totp", totpCode = null),
            )
        }
        val request = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = emptyList(),
            maxInlineSuggestionsCount = 0,
            packageName = "com.android.chrome",
            partition = AutofillPartition.Login(views = listOf(otpView)),
            uri = site,
        )

        val filled = FilledDataBuilderImpl(autofillCipherProvider = provider).build(request)

        assertEquals(1, filled.filledPartitions.size)
        assertEquals("with-totp", filled.filledPartitions.single().autofillCipher.name)
        assertEquals(listOf(filledCode), filled.filledPartitions.single().filledItems)
        coVerify(exactly = 1) { provider.getLoginAutofillCiphers(uri = site, includeTotpCode = true) }
    }

    @Test
    fun `a login form without an OTP field never computes TOTP codes`() = runTest {
        val passwordView: AutofillView.Login.Password = mockk {
            every { this@mockk.data } returns data()
            every { buildFilledItemOrNull(any()) } returns mockk()
        }
        val provider: AutofillCipherProvider = mockk {
            coEvery { isVaultLocked() } returns false
            coEvery { getLoginAutofillCiphers(uri = site, includeTotpCode = false) } returns emptyList()
        }
        FilledDataBuilderImpl(autofillCipherProvider = provider).build(
            AutofillRequest.Fillable(
                ignoreAutofillIds = emptyList(),
                inlinePresentationSpecs = emptyList(),
                maxInlineSuggestionsCount = 0,
                packageName = null,
                partition = AutofillPartition.Login(views = listOf(passwordView)),
                uri = site,
            ),
        )
        coVerify(exactly = 1) { provider.getLoginAutofillCiphers(uri = site, includeTotpCode = false) }
    }

    @Test
    fun `an OTP field is never part of a save`() {
        val partition = AutofillPartition.Login(
            views = listOf(AutofillView.Login.Totp(data = data(text = fakeCode))),
        )
        assertTrue(partition.optionalSaveIds.isEmpty())
        assertTrue(partition.requiredSaveIds.isEmpty())
    }

    // ── SaveInfo ──────────────────────────────────────────────────────

    private val settingsRepository: com.x8bit.bitwarden.data.platform.repository.SettingsRepository =
        mockk { every { isAutofillSavePromptDisabled } returns false }
    private val fillRequest: FillRequest = mockk {
        every { id } returns 1
        every { flags } returns 0
    }

    @Test
    fun `a username-only screen delays its save for the password screen`() {
        val saveInfo: SaveInfo = mockk()
        every { anyConstructed<SaveInfo.Builder>().build() } returns saveInfo
        mockBuilder<SaveInfo.Builder> { it.setFlags(SaveInfo.FLAG_DELAY_SAVE) }
        val partition = AutofillPartition.Login(views = listOf(AutofillView.Login.Username(data = data())))

        val actual = SaveInfoBuilderImpl(settingsRepository).build(partition, fillRequest, "com.example.app")

        assertEquals(saveInfo, actual)
        verify(exactly = 1) { anyConstructed<SaveInfo.Builder>().setFlags(SaveInfo.FLAG_DELAY_SAVE) }
    }

    @Test
    fun `a one-time-code screen offers no save at all`() {
        val partition = AutofillPartition.Login(views = listOf(AutofillView.Login.Totp(data = data())))
        assertNull(SaveInfoBuilderImpl(settingsRepository).build(partition, fillRequest, "com.example.app"))
    }

    // ── the save request ──────────────────────────────────────────────

    @Test
    fun `a two-screen login is saved with the username from the first screen and the app's own URI`() {
        val firstScreen: AssistStructure = mockk()
        val secondScreen: AssistStructure = mockk()
        val usernameRequest = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = null,
            maxInlineSuggestionsCount = 0,
            packageName = "com.example.webapp",
            partition = AutofillPartition.Login(
                views = listOf(AutofillView.Login.Username(data = data(text = "someone"))),
            ),
            uri = site,
        )
        val passwordRequest = usernameRequest.copy(
            partition = AutofillPartition.Login(
                views = listOf(AutofillView.Login.Password(data = data(text = "fake-pw"))),
            ),
        )
        val appInfo = AutofillAppInfo(context = mockk(), packageName = "com.diegonmarcos.cloudvault", sdkInt = 34)
        val parser: AutofillParser = mockk {
            every { parse(autofillAppInfo = appInfo, assistStructure = firstScreen) } returns usernameRequest
            every { parse(autofillAppInfo = appInfo, assistStructure = secondScreen) } returns passwordRequest
        }
        val unverifiedApp = object : AutofillUriResolver {
            override suspend fun resolveForFill(uri: String?, packageName: String?) = uri
            override fun resolveForSave(uri: String?, packageName: String?) =
                AutofillUriPolicy.decideForSave(uri, packageName, isKnownBrowser = false, isFleetSigned = false)
        }
        val processor = AutofillProcessorImpl(
            dispatcherManager = FakeDispatcherManager(unconfined = StandardTestDispatcher()),
            policyManager = mockk {
                every { getActivePolicies(PolicyType.ORGANIZATION_DATA_OWNERSHIP) } returns emptyList()
            },
            filledDataBuilder = mockk(),
            fillResponseBuilder = mockk(),
            parser = parser,
            saveInfoBuilder = mockk(),
            settingsRepository = settingsRepository,
            uriResolver = unverifiedApp,
        )
        val saved = slot<AutofillSaveItem>()
        val intentSender: IntentSender = mockk()
        every {
            createAutofillSavedItemIntentSender(autofillAppInfo = appInfo, autofillSaveItem = capture(saved))
        } returns intentSender
        val callback: SaveCallback = mockk { every { onSuccess(intentSender) } just runs }
        val request: SaveRequest = mockk {
            every { fillContexts } returns listOf(
                mockk<FillContext> { every { structure } returns firstScreen },
                mockk<FillContext> { every { structure } returns secondScreen },
            )
        }

        processor.processSaveRequest(autofillAppInfo = appInfo, request = request, saveCallback = callback)

        assertEquals(
            AutofillSaveItem.Login(
                username = "someone",
                password = "fake-pw",
                uri = "androidapp://com.example.webapp",
            ),
            saved.captured,
        )
        verify(exactly = 1) { callback.onSuccess(intentSender) }
    }
}
