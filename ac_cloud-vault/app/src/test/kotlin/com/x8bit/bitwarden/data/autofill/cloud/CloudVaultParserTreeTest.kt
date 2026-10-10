package com.x8bit.bitwarden.data.autofill.cloud

import android.app.assist.AssistStructure
import android.content.Context
import android.view.View
import android.view.autofill.AutofillId
import com.bitwarden.core.data.manager.model.FlagKey
import com.x8bit.bitwarden.data.autofill.manager.FillAssistManager
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.parser.AutofillParserImpl
import com.x8bit.bitwarden.data.platform.manager.FeatureFlagManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The real parser and the real ViewNode rules, fed fake `AssistStructure` trees (mocked nodes,
 * no static mocks): what a native login screen, an SMS-code screen, a browser page and the
 * vault's own screen turn into. HTML attributes are covered by CloudFieldClassifierTest
 * (android.util.Pair cannot be built on a plain JVM).
 */
class CloudVaultParserTreeTest {

    private val settingsRepository: SettingsRepository = mockk {
        every { isInlineAutofillEnabled } returns false
        every { isFillAssistEnabled } returns false
        every { blockedAutofillUris } returns emptyList()
    }
    private val featureFlagManager: FeatureFlagManager = mockk {
        every { getFeatureFlag(FlagKey.FillAssistTargetingRules) } returns false
    }
    private val fillAssistManager: FillAssistManager = mockk()
    private val parser = AutofillParserImpl(
        settingsRepository = settingsRepository,
        fillAssistManager = fillAssistManager,
        featureFlagManager = featureFlagManager,
    )
    private val appInfo = AutofillAppInfo(
        context = mockk<Context>(),
        packageName = "com.diegonmarcos.cloudvault",
        sdkInt = 34,
    )

    @Suppress("LongParameterList")
    private fun node(
        className: String? = "android.widget.EditText",
        autofillHints: Array<String>? = null,
        inputType: Int = 0,
        idEntry: String? = null,
        hint: String? = null,
        focused: Boolean = false,
        idPackage: String? = null,
        webDomain: String? = null,
        children: List<AssistStructure.ViewNode> = emptyList(),
    ): AssistStructure.ViewNode {
        val id: AutofillId = mockk()
        return mockk {
            every { this@mockk.autofillId } returns id
            every { this@mockk.className } returns className
            every { this@mockk.autofillHints } returns autofillHints
            every { this@mockk.inputType } returns inputType
            every { this@mockk.idEntry } returns idEntry
            every { this@mockk.hint } returns hint
            every { this@mockk.isFocused } returns focused
            every { this@mockk.idPackage } returns idPackage
            every { this@mockk.webDomain } returns webDomain
            every { this@mockk.webScheme } returns if (webDomain != null) "https" else null
            every { this@mockk.htmlInfo } returns null
            every { this@mockk.autofillOptions } returns null
            every { this@mockk.autofillType } returns View.AUTOFILL_TYPE_TEXT
            every { this@mockk.autofillValue } returns null
            every { this@mockk.childCount } returns children.size
            children.forEachIndexed { i, child -> every { this@mockk.getChildAt(i) } returns child }
        }
    }

    private fun screen(root: AssistStructure.ViewNode): AssistStructure {
        val window: AssistStructure.WindowNode = mockk {
            every { rootViewNode } returns root
            every { title } returns null
        }
        return mockk {
            every { windowNodeCount } returns 1
            every { getWindowNodeAt(0) } returns window
        }
    }

    private fun parse(root: AssistStructure.ViewNode): AutofillRequest =
        parser.parse(autofillAppInfo = appInfo, assistStructure = screen(root))

    private val textEmail = 0x21
    private val textPassword = 0x81
    private val number = 0x02
    private val numberPassword = 0x12

    @Test
    fun `a native login screen gives a login partition matched by the app's package`() {
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.example.app",
            children = listOf(
                node(inputType = textEmail, idEntry = "email"),
                node(inputType = textPassword, idEntry = "password", focused = true),
            ),
        )
        val request = parse(root) as AutofillRequest.Fillable
        assertEquals("androidapp://com.example.app", request.uri)
        val views = (request.partition as AutofillPartition.Login).views
        assertTrue(views.any { it is AutofillView.Login.Username }, views.toString())
        assertTrue(views.any { it is AutofillView.Login.Password }, views.toString())
    }

    @Test
    fun `an SMS code screen gives a one-time-code view, not a username`() {
        // Upstream alone reads "phone" in the hint as a username field.
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.example.bank",
            children = listOf(
                node(
                    inputType = number,
                    idEntry = "sms_code",
                    hint = "Enter the code we sent to your phone",
                    focused = true,
                ),
            ),
        )
        val request = parse(root) as AutofillRequest.Fillable
        val views = (request.partition as AutofillPartition.Login).views
        assertEquals(1, views.size)
        assertTrue(views.single() is AutofillView.Login.Totp, views.toString())
    }

    @Test
    fun `the androidx SMS OTP hint gives a one-time-code view`() {
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.example.app",
            children = listOf(node(autofillHints = arrayOf("smsOTPCode"), inputType = number, focused = true)),
        )
        val views = ((parse(root) as AutofillRequest.Fillable).partition as AutofillPartition.Login).views
        assertTrue(views.single() is AutofillView.Login.Totp, views.toString())
    }

    @Test
    fun `a number-password field is a password`() {
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.example.app",
            children = listOf(node(inputType = numberPassword, idEntry = "account_pin", focused = true)),
        )
        val views = ((parse(root) as AutofillRequest.Fillable).partition as AutofillPartition.Login).views
        assertTrue(views.single() is AutofillView.Login.Password, views.toString())
    }

    @Test
    fun `a browser page is matched by the web domain its WebView reports`() {
        val webView = node(
            className = "android.webkit.WebView",
            webDomain = "login.example.com",
            children = listOf(
                node(inputType = textEmail, idEntry = null, hint = "Email"),
                node(inputType = textPassword, focused = true),
            ),
        )
        val root = node(
            className = "android.widget.FrameLayout",
            idPackage = CLOUD_BROWSER_PACKAGE,
            children = listOf(webView),
        )
        val request = parse(root) as AutofillRequest.Fillable
        assertEquals("https://login.example.com", request.uri)
        assertEquals(CLOUD_BROWSER_PACKAGE, request.packageName)
    }

    @Test
    fun `a search box and a plain text field are not filled`() {
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.example.app",
            children = listOf(
                node(inputType = 0x01, idEntry = "search_query", focused = true),
                node(inputType = 0x01, idEntry = "notes"),
            ),
        )
        assertEquals(AutofillRequest.Unfillable, parse(root))
    }

    @Test
    fun `the vault never fills its own screens`() {
        val root = node(
            className = "android.widget.LinearLayout",
            idPackage = "com.diegonmarcos.cloudvault",
            children = listOf(node(inputType = textPassword, idEntry = "password", focused = true)),
        )
        assertEquals(AutofillRequest.Unfillable, parse(root))
    }
}
