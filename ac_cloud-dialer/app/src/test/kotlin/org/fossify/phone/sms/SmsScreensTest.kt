package org.fossify.phone.sms

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI tests of the three SMS screens (list, conversation, new message), rendered for real
 * under Robolectric. The screens are stateless, so each test owns the state and the callbacks and
 * asserts what a tap or a keystroke asks of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
class SmsScreensTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val palette = KitPalette(
        surface = Color(0xFF222222), surfaceSelected = Color(0xFF333333), textPrimary = Color.White, textSecondary = Color(0xFFAAAAAA),
        accent = Color(0xFF66CCFF), hairline = Color(0xFF444444), tileInk = Color.Black,
    )

    private fun conversation(id: Long, title: String, snippet: String, unread: Int = 0, pinned: Boolean = false) = Conversation(
        threadId = id, addresses = listOf("+1555000$id"), title = title, snippet = snippet, date = 1_700_000_000_000L + id,
        unreadCount = unread, messageCount = 3, pinned = pinned, archived = false, hasFailed = false,
    )

    private fun msg(id: Long, type: Int, body: String, status: Int = SmsStatusCode.NONE, date: Long = 1_700_000_000_000L + id) =
        SmsMessage(id, 1, "+15550001", body, date, type, true, status)

    // ---- list ----------------------------------------------------------------------------

    @Test
    fun listShowsConversationsUnreadBadgeAndOpensOne() {
        var opened: Long? = null
        val rows = listOf(conversation(1, "Ann", "see you at eight", unread = 2), conversation(2, "Bob", "ok"))
        compose.setContent {
            CloudKitTheme(palette) {
                SmsListScreen(rows, true, true, true, false, { null }, { opened = it.threadId }, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag(SmsTags.row(1)).assertIsDisplayed()
        compose.onNodeWithTag(SmsTags.row(2)).assertIsDisplayed()
        compose.onNodeWithText("see you at eight").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed() // the unread badge
        compose.onNodeWithTag(SmsTags.row(2)).performClick()
        assertEquals(2L, opened)
    }

    @Test
    fun listOffersMakeDefaultWhenNotTheDefaultApp() {
        var asked = 0
        compose.setContent {
            CloudKitTheme(palette) {
                SmsListScreen(listOf(conversation(1, "Ann", "hi")), true, false, true, false, { null }, {}, {}, {}, {}, {}, {}, {}, { asked++ })
            }
        }
        compose.onNodeWithTag("kit:banner:" + SmsTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag("sms:make-default").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun listAsksForTheGrantBeforeAnythingElse() {
        var granted = 0
        compose.setContent {
            CloudKitTheme(palette) {
                SmsListScreen(emptyList(), true, false, false, false, { null }, {}, {}, {}, {}, {}, {}, { granted++ }, {})
            }
        }
        compose.onNodeWithTag("sms:grant").performClick()
        assertEquals(1, granted)
    }

    @Test
    fun emptyListSaysSoAndNewMessageIsOneTapAway() {
        var newMessage = 0
        compose.setContent {
            CloudKitTheme(palette) {
                SmsListScreen(emptyList(), true, true, true, false, { null }, {}, {}, {}, {}, { newMessage++ }, {}, {}, {})
            }
        }
        compose.onNodeWithTag(SmsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(SmsTags.FAB).performClick()
        assertEquals(1, newMessage)
    }

    // ---- conversation --------------------------------------------------------------------

    private fun showConversation(
        messages: List<SmsMessage>, isDefault: Boolean = true, onRetry: (SmsMessage) -> Unit = {}, onSend: () -> Unit = {},
        sims: List<SimInfo> = emptyList(), onPickSim: (SimInfo) -> Unit = {}, draftState: () -> String = { "" }, setDraft: (String) -> Unit = {},
    ) {
        compose.setContent {
            CloudKitTheme(palette) {
                ConversationScreen(
                    title = "Ann", messages = messages, isDefault = isDefault, canRead = true, sims = sims, selectedSim = sims.firstOrNull(),
                    draft = draftState(), pendingImage = null, onDraftChange = setDraft, onSend = onSend, onAttach = {}, onClearAttachment = {},
                    onPickSim = onPickSim, onRetry = onRetry, onDelete = {}, onBack = {}, onGrant = {}, onMakeDefault = {},
                )
            }
        }
    }

    @Test
    fun conversationShowsBubblesWithTheirDeliveryStatus() {
        showConversation(
            listOf(
                msg(1, SmsType.INBOX, "are you coming?"), msg(2, SmsType.SENT, "yes", SmsStatusCode.COMPLETE),
                msg(3, SmsType.OUTBOX, "on my way"), msg(4, SmsType.SENT, "sent only"),
            ),
        )
        compose.onNodeWithTag(SmsTags.bubble(1)).assertIsDisplayed()
        compose.onNodeWithText("are you coming?").assertIsDisplayed()
        compose.onNodeWithText("yes").assertIsDisplayed()
        compose.onNodeWithText("on my way").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Delivered", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Sending", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFailedMessageOffersRetry() {
        var retried: Long? = null
        showConversation(listOf(msg(5, SmsType.FAILED, "did not go")), onRetry = { retried = it.id })
        compose.onNodeWithText("did not go").assertIsDisplayed()
        compose.onNodeWithTag(SmsTags.retry(5)).performClick()
        assertEquals(5L, retried)
    }

    @Test
    fun typingFeedsTheDraftAndSendIsDisabledUntilThereIsText() {
        var sent = 0
        var draft by mutableStateOf("")
        showConversation(emptyList(), onSend = { sent++ }, draftState = { draft }, setDraft = { draft = it })
        compose.onNodeWithTag(SmsTags.CONV_SEND).assertIsNotEnabled()
        compose.onNodeWithTag(SmsTags.CONV_INPUT).performTextInput("hello")
        compose.waitForIdle()
        assertEquals("hello", draft)
        compose.onNodeWithTag(SmsTags.CONV_SEND).assertIsEnabled()
        compose.onNodeWithTag(SmsTags.CONV_SEND).performClick()
        assertEquals(1, sent)
    }

    @Test
    fun withoutTheDefaultRoleTheConversationIsReadOnly() {
        showConversation(listOf(msg(1, SmsType.INBOX, "read me")), isDefault = false)
        compose.onNodeWithText("read me").assertIsDisplayed()
        compose.onNodeWithTag("sms:make-default").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(SmsTags.CONV_INPUT).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun twoSimsOfferAPicker() {
        val a = SimInfo(11, 0, "Home")
        val b = SimInfo(22, 1, "Work")
        var picked: SimInfo? = null
        showConversation(emptyList(), sims = listOf(a, b), onPickSim = { picked = it })
        compose.onNodeWithTag(SmsTags.CONV_SIM).performClick()
        compose.onNodeWithText("SIM 2 · Work").performClick()
        assertEquals(b, picked)
    }

    // ---- new message ---------------------------------------------------------------------

    @Test
    fun newMessageSuggestsContactsAndAddsOneOnTap() {
        var added: String? = null
        val all = listOf(Recipient("Ann Lee", "+15550001", null), Recipient("Bob", "+15550002", null))
        compose.setContent {
            CloudKitTheme(palette) {
                NewMessageScreen(
                    recipients = emptyList(), query = "", suggestions = all, draft = "", pendingImage = null, isDefault = true, canRead = true,
                    sims = emptyList(), selectedSim = null, onQueryChange = {}, onAddRecipient = { added = it }, onRemoveRecipient = {},
                    onDraftChange = {}, onSend = {}, onAttach = {}, onClearAttachment = {}, onPickSim = {}, onBack = {}, onGrant = {}, onMakeDefault = {},
                )
            }
        }
        compose.onNodeWithText("Ann Lee").assertIsDisplayed()
        compose.onNodeWithTag(SmsTags.suggestion(1)).performClick()
        assertEquals("+15550002", added)
    }

    @Test
    fun newMessageShowsChosenRecipientsAndSends() {
        var sent = 0
        var removed = -1
        compose.setContent {
            CloudKitTheme(palette) {
                NewMessageScreen(
                    recipients = listOf("+15550001"), query = "", suggestions = emptyList(), draft = "hi", pendingImage = null, isDefault = true, canRead = true,
                    sims = emptyList(), selectedSim = null, onQueryChange = {}, onAddRecipient = {}, onRemoveRecipient = { removed = it },
                    onDraftChange = {}, onSend = { sent++ }, onAttach = {}, onClearAttachment = {}, onPickSim = {}, onBack = {}, onGrant = {}, onMakeDefault = {},
                )
            }
        }
        compose.onNodeWithText("+15550001").assertIsDisplayed()
        compose.onNodeWithTag(SmsTags.NEW_SEND).performClick()
        assertEquals(1, sent)
        assertEquals(-1, removed)
    }
}
