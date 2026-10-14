package app.sterna.ui.components

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.sterna.R
import app.sterna.core.data.settings.AppStyle
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.text.TextTool
import app.sterna.ui.theme.GmailDarkColorScheme
import app.sterna.ui.theme.LocalMailListPalette
import app.sterna.ui.theme.MailListPalette
import app.sterna.ui.theme.SternaTheme
import app.sterna.ui.theme.gmailDarkColorScheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.annotation.GraphicsMode

/**
 * Gmail Dark's conversation row, RENDERED: the real [EmailListItem] under the real [SternaTheme] with
 * app_style = Gmail Dark, asked about its structure and its pixels.
 *
 *  - a rounded card one step lighter than the page (corner pixel = page, inside = card);
 *  - the snippet is grey, regular and NOT italic, on the line under the subject;
 *  - unread: bold sender/subject/time and a filled dot right of the time in the scheme's primary;
 *  - the thread count is a plain grey number, not the Cloud pill;
 *  - the fourth line carries the ✨ and copy chips beside the file chip, and is absent when empty;
 *  - the colours are the dynamic (Material You) scheme on Android 12+, Gmail's own palette below.
 * And the other two styles still draw the Cloud row: italic indented preview, pill, `|` bar.
 * mdpi keeps px == dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GmailDarkRowRenderedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val app get() = RuntimeEnvironment.getApplication()

    private val pdf = EmailBodyPart(
        blobId = "b1", partId = "2", size = 1234, type = "application/pdf", name = "invoice.pdf", disposition = "attachment",
    )

    // No '@' in the address: the avatar's domain-logo lookup has no domain to fetch, so the row
    // falls to its placeholder without touching the network.
    private fun email(parts: List<EmailBodyPart> = listOf(pdf), flagged: Boolean = false) = Email(
        id = "e1",
        accountId = "a",
        subject = "Security alert for your repository",
        preview = "A new dependency advisory was published for one of your projects.",
        receivedAt = "2026-09-24T10:00:00Z",
        from = listOf(EmailAddress(name = "GitHub", email = "noreply")),
        hasAttachment = parts.isNotEmpty(),
        attachments = parts,
        keywords = buildMap { if (flagged) put("\$flagged", true) },
    )

    private lateinit var hostView: View
    private lateinit var scheme: ColorScheme
    private lateinit var palette: MailListPalette

    private fun show(
        style: AppStyle,
        unread: Boolean = true,
        mail: Email = email(),
        withLoader: Boolean = true,
        label: String? = "GitHub Alerts",
    ) {
        compose.setContent {
            SternaTheme(themeMode = ThemeMode.DARK, appStyle = style) {
                scheme = MaterialTheme.colorScheme
                palette = LocalMailListPalette.current
                hostView = LocalView.current
                Box(Modifier.fillMaxSize().background(palette.pane)) {
                    EmailListItem(
                        email = mail,
                        onClick = {},
                        unread = unread,
                        threadCount = 3,
                        originLabel = label,
                        onToggleFavourite = {},
                        onOpenAttachment = {},
                        onLoadMessage = if (withLoader) ({ it }) else null,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun bounds(t: String): Rect = tag(t).also { it.assertIsDisplayed() }.fetchSemanticsNode().boundsInRoot

    private fun styleOf(node: SemanticsNodeInteraction): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().layoutInput.style
    }

    private fun bitmap(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { hostView.draw(Canvas(it)) }
    }

    private fun near(argb: Int, c: Color, tol: Int = 6): Boolean {
        val e = c.toArgb()
        return listOf(16, 8, 0).all { s -> abs(((argb shr s) and 0xFF) - ((e shr s) and 0xFF)) <= tol }
    }

    @Test
    fun `gmail dark draws a rounded card one step lighter than the page`() {
        show(AppStyle.GMAIL_DARK)
        assertEquals("the page is the scheme's surface", scheme.surface, palette.pane)
        assertEquals("the card is the scheme's surfaceContainer", scheme.surfaceContainer, palette.card)
        assertNotEquals("the card must stand off the page", palette.pane, palette.card)
        val card = bounds(GmailRowTags.CARD)
        val px = bitmap()
        val corner = px.getPixel(card.left.roundToInt() + 1, card.top.roundToInt() + 1)
        val inside = px.getPixel(card.left.roundToInt() + 4, card.center.y.roundToInt())
        assertTrue("the card's corner is rounded off: page shows there (${Integer.toHexString(corner)})", near(corner, palette.pane))
        assertTrue("inside the card is the card colour (${Integer.toHexString(inside)})", near(inside, palette.card))
        assertTrue("the card leaves page visible beside it", card.left > 0f && card.right < hostView.width)
    }

    @Test
    fun `gmail dark row - unread is bold with a filled dot in the dynamic primary`() {
        show(AppStyle.GMAIL_DARK, unread = true)
        for (t in listOf(GmailRowTags.SENDER, GmailRowTags.SUBJECT, GmailRowTags.TIME)) {
            assertEquals("$t is bold on an unread row", FontWeight.Bold, styleOf(tag(t)).fontWeight)
        }
        val dot = bounds(GmailRowTags.UNREAD_DOT)
        val time = bounds(GmailRowTags.TIME)
        assertTrue("the dot sits right of the time", dot.left >= time.right)
        val px = bitmap().getPixel(dot.center.x.roundToInt(), dot.center.y.roundToInt())
        assertTrue("the dot is filled with the scheme's primary", near(px, scheme.primary))
        assertEquals(
            "Android 12+: the scheme IS the wallpaper's dynamic dark scheme",
            dynamicDarkColorScheme(app).primary, scheme.primary,
        )
    }

    @Test
    fun `gmail dark row - read is regular with no dot`() {
        show(AppStyle.GMAIL_DARK, unread = false)
        for (t in listOf(GmailRowTags.SENDER, GmailRowTags.SUBJECT, GmailRowTags.TIME)) {
            assertEquals("$t is regular on a read row", FontWeight.Normal, styleOf(tag(t)).fontWeight)
        }
        compose.onAllNodesWithTag(GmailRowTags.UNREAD_DOT, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `gmail dark row - snippet is grey, regular, not italic, aligned under the sender, label at its end`() {
        show(AppStyle.GMAIL_DARK)
        val snippet = styleOf(tag(GmailRowTags.SNIPPET))
        assertTrue("the snippet is not italic", snippet.fontStyle == null || snippet.fontStyle == FontStyle.Normal)
        assertEquals("the snippet is regular weight", FontWeight.Normal, snippet.fontWeight ?: FontWeight.Normal)
        assertEquals("the snippet is the grey onSurfaceVariant", scheme.onSurfaceVariant, snippet.color)
        val s = bounds(GmailRowTags.SNIPPET)
        assertEquals("not indented: the snippet starts under the sender", bounds(GmailRowTags.SENDER).left, s.left, 0.5f)
        assertTrue("under the subject", s.top >= bounds(GmailRowTags.SUBJECT).bottom - 0.5f)
        val label = bounds(GmailRowTags.LABEL)
        compose.onNodeWithText("GitHub Alerts", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("the label chip ends the snippet line", label.left >= s.right && label.top < s.bottom && label.bottom > s.top)
    }

    @Test
    fun `gmail dark row - thread count is plain grey text, not a pill`() {
        show(AppStyle.GMAIL_DARK)
        val count = tag(GmailRowTags.COUNT)
        count.assertIsDisplayed()
        val style = styleOf(count)
        assertEquals(scheme.onSurfaceVariant, style.color)
        assertTrue("the count follows the sender", bounds(GmailRowTags.COUNT).left >= bounds(GmailRowTags.SENDER).right)
        val px = bitmap()
        val b = bounds(GmailRowTags.COUNT)
        val behind = px.getPixel(b.left.roundToInt(), b.top.roundToInt())
        assertTrue("no pill fill behind the number: the card shows through", near(behind, palette.card))
    }

    @Test
    fun `gmail dark row - the star is an outline on the subject line, silhouette avatar, no letter circle`() {
        show(AppStyle.GMAIL_DARK)
        val star = bounds(GmailRowTags.STAR)
        val subject = bounds(GmailRowTags.SUBJECT)
        assertEquals("the star is centred on the subject line", subject.center.y, star.center.y, 1f)
        assertTrue("the star sits right of the subject", star.left >= subject.right)
        tag(GmailRowTags.AVATAR_SILHOUETTE).assertIsDisplayed()
        compose.onNodeWithText("G", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `gmail dark row - the fourth line holds the AI and copy chips beside the file chip`() {
        show(AppStyle.GMAIL_DARK)
        val line = bounds(GmailRowTags.CHIP_LINE)
        val snippet = bounds(GmailRowTags.SNIPPET)
        assertTrue("the chip line is under the snippet", line.top >= snippet.bottom)
        val ai = bounds(GmailRowTags.CHIP_AI)
        val copy = bounds(GmailRowTags.CHIP_COPY)
        val file = bounds(GmailRowTags.CHIP_FILE)
        assertTrue("AI, then copy, then the file, on one line", ai.right <= copy.left && copy.right <= file.left)
        assertEquals("one line", ai.top, file.top, 0.5f)
        assertEquals("compact: all three chips are the same height", ai.height, file.height, 0.5f)
        compose.onNodeWithContentDescription(app.getString(TextTool.RESUME.label), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.message_copy_code), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.a11y_open_attachment, pdf.name), useUnmergedTree = true).assertIsDisplayed()
        // None of the Cloud row's furniture.
        compose.onAllNodesWithText("|", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `gmail dark row - the fourth line is only drawn when something is on it`() {
        show(AppStyle.GMAIL_DARK, mail = email(parts = emptyList()).copy(preview = null), withLoader = false, label = null)
        compose.onAllNodesWithTag(GmailRowTags.CHIP_LINE, useUnmergedTree = true).assertCountEquals(0)
        assertFalse(gmailChipLineShown(hasMessageToActOn = false, fileChips = 0))
        assertTrue(gmailChipLineShown(hasMessageToActOn = false, fileChips = 1))
        assertTrue(gmailChipLineShown(hasMessageToActOn = true, fileChips = 0))
    }

    @Test
    fun `gmail dark falls back to Gmail's own dark palette below Android 12`() {
        // ColorScheme has no value equality: below 12 it is the very same object, from 12 the
        // wallpaper's roles, compared role by role.
        assertTrue("below Android 12: Gmail's own palette", GmailDarkColorScheme === gmailDarkColorScheme(app, sdkInt = 30))
        val dynamic = dynamicDarkColorScheme(app)
        val picked = gmailDarkColorScheme(app, sdkInt = 31)
        for ((role, pair) in mapOf(
            "primary" to (dynamic.primary to picked.primary),
            "secondaryContainer" to (dynamic.secondaryContainer to picked.secondaryContainer),
            "surface" to (dynamic.surface to picked.surface),
            "surfaceContainer" to (dynamic.surfaceContainer to picked.surfaceContainer),
            "onSurfaceVariant" to (dynamic.onSurfaceVariant to picked.onSurfaceVariant),
        )) assertEquals("Android 12+: $role is the wallpaper's", pair.first, pair.second)
    }

    @Test
    fun `cloud dark keeps its own row - italic indented preview, pill, bar`() = assertCloudRow(AppStyle.CLOUD_DARK)

    @Test
    fun `cloud e-ink keeps its own row - italic indented preview, pill, bar`() = assertCloudRow(AppStyle.CLOUD_EINK)

    private fun assertCloudRow(style: AppStyle) {
        show(style)
        compose.onAllNodesWithTag(GmailRowTags.CARD, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(GmailRowTags.CHIP_LINE, useUnmergedTree = true).assertCountEquals(0)
        val preview = compose.onNodeWithText(email().preview!!, useUnmergedTree = true)
        assertEquals("$style: the preview is still italic", FontStyle.Italic, styleOf(preview).fontStyle)
        compose.onNodeWithText("|", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(TextTool.RESUME.label), useUnmergedTree = true).assertIsDisplayed()
    }
}
