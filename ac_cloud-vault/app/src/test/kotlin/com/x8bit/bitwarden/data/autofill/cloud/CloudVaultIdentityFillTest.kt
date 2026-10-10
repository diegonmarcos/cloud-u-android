package com.x8bit.bitwarden.data.autofill.cloud

import android.app.assist.AssistStructure
import android.content.Context
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.bitwarden.core.data.manager.model.FlagKey
import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.vault.CipherListView
import com.bitwarden.vault.CipherListViewType
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.DecryptCipherListResult
import com.bitwarden.vault.FieldType
import com.bitwarden.vault.FieldView
import com.bitwarden.vault.IdentityView
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.autofill.builder.FilledDataBuilderImpl
import com.x8bit.bitwarden.data.autofill.manager.FillAssistManager
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillCipher
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillSelectionData
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.model.FilledData
import com.x8bit.bitwarden.data.autofill.model.FilledItem
import com.x8bit.bitwarden.data.autofill.parser.AutofillParserImpl
import com.x8bit.bitwarden.data.autofill.provider.AutofillCipherProvider
import com.x8bit.bitwarden.data.autofill.provider.AutofillCipherProviderImpl
import com.x8bit.bitwarden.data.autofill.util.buildFilledItemOrNull
import com.x8bit.bitwarden.data.autofill.util.fillableAutofillIds
import com.x8bit.bitwarden.data.platform.manager.FeatureFlagManager
import com.x8bit.bitwarden.data.platform.manager.PolicyManager
import com.x8bit.bitwarden.data.platform.manager.ciphermatching.CipherMatchingManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.util.subtitle
import com.x8bit.bitwarden.data.vault.manager.model.GetCipherResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockData
import com.x8bit.bitwarden.data.vault.repository.util.statusFor
import com.x8bit.bitwarden.ui.platform.feature.rootnav.util.toVaultItemListingType
import com.x8bit.bitwarden.ui.vault.model.VaultItemListingType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ID-document fields end to end: which screens become an Identity partition (the real parser on
 * fake node trees), which Identity items are offered and what they fill (the real data builder),
 * that nothing is read while the vault is locked (the real cipher provider), and that only the
 * document fields carry the suggestion. Every value is an obvious fake.
 */
class CloudVaultIdentityFillTest {

    // ── parser: fake native screens ───────────────────────────────────

    private val settingsRepository: SettingsRepository = mockk {
        every { isInlineAutofillEnabled } returns false
        every { isFillAssistEnabled } returns false
        every { blockedAutofillUris } returns emptyList()
    }
    private val featureFlagManager: FeatureFlagManager = mockk {
        every { getFeatureFlag(FlagKey.FillAssistTargetingRules) } returns false
    }
    private val parser = AutofillParserImpl(
        settingsRepository = settingsRepository,
        fillAssistManager = mockk<FillAssistManager>(),
        featureFlagManager = featureFlagManager,
    )
    private val appInfo = AutofillAppInfo(
        context = mockk<Context>(),
        packageName = "com.diegonmarcos.cloudvault",
        sdkInt = 34,
    )
    private val text = 0x01
    private val textPassword = 0x81

    private fun node(
        className: String = "android.widget.EditText",
        inputType: Int = text,
        idEntry: String? = null,
        focused: Boolean = false,
        idPackage: String? = null,
        children: List<AssistStructure.ViewNode> = emptyList(),
    ): AssistStructure.ViewNode {
        val id: AutofillId = mockk()
        return mockk {
            every { this@mockk.autofillId } returns id
            every { this@mockk.className } returns className
            every { this@mockk.autofillHints } returns null
            every { this@mockk.inputType } returns inputType
            every { this@mockk.idEntry } returns idEntry
            every { this@mockk.hint } returns null
            every { this@mockk.isFocused } returns focused
            every { this@mockk.idPackage } returns idPackage
            every { this@mockk.webDomain } returns null
            every { this@mockk.webScheme } returns null
            every { this@mockk.htmlInfo } returns null
            every { this@mockk.autofillOptions } returns null
            every { this@mockk.autofillType } returns View.AUTOFILL_TYPE_TEXT
            every { this@mockk.autofillValue } returns null
            every { this@mockk.childCount } returns children.size
            children.forEachIndexed { i, child -> every { this@mockk.getChildAt(i) } returns child }
        }
    }

    private fun parse(vararg fields: AssistStructure.ViewNode): AutofillRequest {
        val root = node(
            className = "android.widget.LinearLayout",
            inputType = 0,
            idPackage = "com.example.gov",
            children = fields.toList(),
        )
        val window: AssistStructure.WindowNode = mockk {
            every { rootViewNode } returns root
            every { title } returns null
        }
        val structure: AssistStructure = mockk {
            every { windowNodeCount } returns 1
            every { getWindowNodeAt(0) } returns window
        }
        return parser.parse(autofillAppInfo = appInfo, assistStructure = structure)
    }

    @Test
    fun `a focused DNI field gives an Identity partition with the screen's name and address`() {
        val request = parse(
            node(idEntry = "first_name"),
            node(idEntry = "dni", focused = true),
            node(idEntry = "postal_code"),
            node(idEntry = "notes"),
        ) as AutofillRequest.Fillable
        val partition = request.partition as AutofillPartition.Identity
        assertEquals(
            listOf(IdentityField.FIRST_NAME, IdentityField.DNI, IdentityField.POSTAL_CODE),
            partition.views.map { it.field },
        )
        assertEquals(listOf(IdentityField.DNI), partition.documentViews.map { it.field })
        // Never a save prompt for an ID document.
        assertFalse(partition.canPerformSaveRequest)
    }

    @Test
    fun `an ID card number is an Identity field, not a payment card`() {
        val request = parse(node(idEntry = "id_card_number", focused = true)) as AutofillRequest.Fillable
        val partition = request.partition as AutofillPartition.Identity
        assertEquals(IdentityField.NATIONAL_ID, partition.views.single().field)
    }

    @Test
    fun `a focused name field never offers Identity items, even next to a DNI field`() {
        assertEquals(
            AutofillRequest.Unfillable,
            parse(
                node(idEntry = "first_name", focused = true),
                node(idEntry = "dni"),
            ),
        )
    }

    @Test
    fun `a DNI above a password is that login's username`() {
        val request = parse(
            node(idEntry = "dni", focused = true),
            node(inputType = textPassword, idEntry = "clave_acceso"),
        ) as AutofillRequest.Fillable
        val views = (request.partition as AutofillPartition.Login).views
        assertTrue(views[0] is AutofillView.Login.Username, views.toString())
        assertTrue(views[1] is AutofillView.Login.Password, views.toString())
    }

    @Test
    fun `an unrelated user ID field stays a login username`() {
        val request = parse(
            node(idEntry = "user_id", focused = true),
            node(inputType = textPassword, idEntry = "password"),
        ) as AutofillRequest.Fillable
        val views = (request.partition as AutofillPartition.Login).views
        // Login views only: no Identity view reaches a login partition.
        assertEquals(2, views.size)
        assertTrue(views.any { it is AutofillView.Login.Username }, views.toString())
    }

    // ── data builder: which Identity items, which values ──────────────

    @BeforeEach
    fun setup() {
        mockkStatic(AutofillView::buildFilledItemOrNull)
        mockkStatic(CipherView::subtitle)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(AutofillView::buildFilledItemOrNull)
        unmockkStatic(CipherView::subtitle)
    }

    private fun identityView(field: IdentityField): Pair<AutofillView.Identity, AutofillId> {
        val id: AutofillId = mockk()
        val data = AutofillView.Data(
            autofillId = id,
            autofillOptions = emptyList(),
            autofillType = View.AUTOFILL_TYPE_TEXT,
            isFocused = field.isDocument,
            textValue = null,
            hasPasswordTerms = false,
            website = null,
        )
        val view: AutofillView.Identity = mockk {
            every { this@mockk.data } returns data
            every { this@mockk.field } returns field
            every { buildFilledItemOrNull(any()) } answers {
                FilledItem(autofillId = id, value = mockk<AutofillValue>())
            }
        }
        return view to id
    }

    private fun identity(name: String, values: IdentityValues) = AutofillCipher.Identity(
        cipherId = name,
        name = name,
        subtitle = "",
        values = values,
    )

    private fun request(partition: AutofillPartition) = AutofillRequest.Fillable(
        ignoreAutofillIds = emptyList(),
        inlinePresentationSpecs = emptyList(),
        maxInlineSuggestionsCount = 0,
        packageName = "com.example.gov",
        partition = partition,
        uri = "androidapp://com.example.gov",
    )

    @Test
    fun `Identity items with the document are offered and only the document field shows them`() =
        runTest {
            val (dniView, dniId) = identityView(IdentityField.DNI)
            val (nameView, nameId) = identityView(IdentityField.FIRST_NAME)
            val (zipView, _) = identityView(IdentityField.POSTAL_CODE)
            val partition = AutofillPartition.Identity(views = listOf(nameView, dniView, zipView))
            val withDni = identity(
                "Spain",
                IdentityValues(
                    firstName = "Testy",
                    customFields = listOf("DNI" to "FAKE-DNI-0001"),
                ),
            )
            val passportOnly = identity("Passport", IdentityValues(passportNumber = "FAKE-PASS-0001"))
            val provider: AutofillCipherProvider = mockk {
                coEvery { isVaultLocked() } returns false
                coEvery { getIdentityAutofillCiphers() } returns listOf(withDni, passportOnly)
            }

            val filled = FilledDataBuilderImpl(autofillCipherProvider = provider).build(request(partition))

            // The passport-only item has nothing for a DNI field: not offered.
            assertEquals(listOf("Spain"), filled.filledPartitions.map { it.autofillCipher.name })
            val dataset = filled.filledPartitions.single()
            // DNI and first name are filled; the item has no postal code, so that field is left.
            assertEquals(setOf(dniId, nameId), dataset.filledItems.map { it.autofillId }.toSet())
            // Only the DNI field carries the suggestion.
            assertEquals(setOf(dniId), dataset.presentationIds)
            // The "open the vault" entry sits on the document field only.
            assertEquals(listOf(dniId), filled.fillableAutofillIds)
            coVerify(exactly = 0) { provider.getLoginAutofillCiphers(any(), any()) }
        }

    @Test
    fun `the vault entry of an Identity request sits on the document field and opens the Identity list`() {
        val (dniView, _) = identityView(IdentityField.DNI)
        val filledData = FilledData(
            filledPartitions = emptyList(),
            ignoreAutofillIds = emptyList(),
            originalPartition = AutofillPartition.Identity(views = listOf(dniView)),
            uri = null,
            vaultItemInlinePresentationSpec = null,
            isVaultLocked = true,
        )
        assertEquals(1, filledData.fillableAutofillIds.size)
        // After unlock, the selection screen lists Identity items (no domain match).
        assertEquals(
            VaultItemListingType.Identity,
            AutofillSelectionData.Type.IDENTITY.toVaultItemListingType(),
        )
    }

    // ── provider: nothing before unlock ───────────────────────────────

    private val activeUserId = "fake-user"
    private val vaultUnlockState = MutableStateFlow<List<VaultUnlockData>>(emptyList())
    private val identityListView: CipherListView = mockk {
        every { id } returns "identity-1"
        every { archivedDate } returns null
        every { deletedDate } returns null
        every { reprompt } returns CipherRepromptType.NONE
        every { type } returns CipherListViewType.Identity
    }
    private val identityCipherView: CipherView = mockk {
        every { id } returns "identity-1"
        every { name } returns "Spain"
        every { identity } returns mockk<IdentityView> {
            every { firstName } returns "Testy"
            every { middleName } returns null
            every { lastName } returns "Fakeson"
            every { address1 } returns null
            every { address2 } returns null
            every { address3 } returns null
            every { city } returns null
            every { state } returns null
            every { postalCode } returns null
            every { country } returns "ES"
            every { ssn } returns null
            every { passportNumber } returns null
            every { licenseNumber } returns null
        }
        every { fields } returns listOf(
            FieldView(name = "DNI", value = "FAKE-DNI-0001", type = FieldType.HIDDEN, linkedId = null),
            FieldView(name = "Valid until", value = "2099-01-01", type = FieldType.TEXT, linkedId = null),
            FieldView(name = "Verified", value = "true", type = FieldType.BOOLEAN, linkedId = null),
        )
    }
    private val vaultRepository: VaultRepository = mockk {
        every { vaultUnlockDataStateFlow } returns vaultUnlockState
        every { isVaultUnlocked(activeUserId) } answers {
            vaultUnlockState.value.statusFor(activeUserId) == VaultUnlockData.Status.UNLOCKED
        }
        every { decryptCipherListResultStateFlow } returns MutableStateFlow(
            DataState.Loaded(
                DecryptCipherListResult(successes = listOf(identityListView), failures = emptyList()),
            ),
        )
        coEvery { getCipher("identity-1") } returns GetCipherResult.Success(identityCipherView)
    }
    private val authRepository: AuthRepository = mockk {
        every { activeUserId } returns this@CloudVaultIdentityFillTest.activeUserId
    }

    private fun provider() = AutofillCipherProviderImpl(
        authRepository = authRepository,
        cipherMatchingManager = mockk<CipherMatchingManager>(),
        vaultRepository = vaultRepository,
        policyManager = mockk<PolicyManager>(),
    )

    @Test
    fun `no Identity item is read or offered while the vault is locked`() = runTest {
        vaultUnlockState.value = emptyList()

        assertTrue(provider().getIdentityAutofillCiphers().isEmpty())
        coVerify(exactly = 0) { vaultRepository.getCipher(any()) }
    }

    @Test
    fun `after unlock every Identity item is offered with its text and hidden custom fields`() =
        runTest {
            vaultUnlockState.value = listOf(
                VaultUnlockData(userId = activeUserId, status = VaultUnlockData.Status.UNLOCKED),
            )
            every { identityCipherView.subtitle } returns "Testy Fakeson"

            val identity = provider().getIdentityAutofillCiphers().single()

            assertEquals("Spain", identity.name)
            assertEquals("FAKE-DNI-0001", IdentityFieldMapping.valueFor(IdentityField.DNI, identity.values))
            assertEquals(
                "2099-01-01",
                IdentityFieldMapping.valueFor(IdentityField.VALID_UNTIL, identity.values),
            )
            assertEquals(listOf("DNI", "Valid until"), identity.values.customFields.map { it.first })
            assertFalse(identity.toString().contains("FAKE-DNI"))
        }
}
