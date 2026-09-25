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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.sterna.R
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.text.TextTool
import app.sterna.ui.theme.LocalMailListPalette
import app.sterna.ui.theme.SternaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
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
import org.robolectric.annotation.GraphicsMode

/**
 * #514 (and #518 before it): "the bottom icons — nothing there". Every earlier tester of this row
 * read SOURCE — is `ListRowActions(` called, is `TextTool.RESUME` before `CopyCodeIcon()` — and
 * every one of them was green while the phone showed an empty line: the icons were composed, in
 * the right order, in BLACK on a black card. Composed is not visible.
 *
 * So this renders the REAL [EmailListItem] under the REAL dark theme (black card, the case that
 * hid them) and asks the pixels. For each of Resume Mail, Copy Code and the attachment chip, some
 * pixels inside its bounds must differ from the card it sits on; the `|` must exist; the row must
 * sit BELOW the preview, in #500's order; and a chip tap must reach the list's handler. The card
 * colour is read from [rowBackground] and the palette, and every label from the app's own string
 * resources — nothing about the row is restated here. mdpi keeps px == dp.
 *
 * Drop the row's `LocalContentColor provides ink` and both icon cases go red: SternaTheme sets no
 * content colour, exactly as the real list pane did not before #518.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmailListItemRow3RenderedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app's manifest has no entry for the activity the compose rule launches.
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

    private val parts = listOf(
        EmailBodyPart(blobId = "b1", partId = "2", size = 1234, type = "application/pdf",
            name = "invoice.pdf", disposition = "attachment"),
    )

    private val email = Email(
        id = "e1",
        subject = "Your sign-in code",
        preview = "Here is the code you asked for.",
        receivedAt = "2026-09-24T10:00:00Z",
        from = listOf(EmailAddress(name = "Alex", email = "alex@example.org")),
        hasAttachment = true,
        attachments = parts,
    )

    private var card = Color.Unspecified
    private lateinit var hostView: View
    private val opened = mutableListOf<EmailBodyPart>()

    private fun show(unread: Boolean) {
        assertTrue("fixture must carry a file, or the chip case proves nothing", email.fileAttachmentParts().isNotEmpty())
        compose.setContent {
            SternaTheme(themeMode = ThemeMode.DARK) {
                val palette = LocalMailListPalette.current
                card = rowBackground(MaterialTheme.colorScheme, selected = false, current = false, flash = 0f, card = palette.card)
                hostView = LocalView.current
                // No avatar: its logo lookup goes to the network, and this is about the third line.
                CompositionLocalProvider(LocalListMonogram provides false) {
                    Box(Modifier.fillMaxSize().background(palette.pane)) {
                        EmailListItem(
                            email = email,
                            onClick = {},
                            unread = unread,
                            onOpenAttachment = { opened += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(label: String, byText: Boolean = false): Rect {
        val node = if (byText) compose.onNodeWithText(label, useUnmergedTree = true)
        else compose.onNodeWithContentDescription(label, useUnmergedTree = true)
        node.assertIsDisplayed()
        return node.fetchSemanticsNode().boundsInRoot
    }

    /** How many pixels inside [b] a reader can tell apart from the card. */
    private fun inkedPixels(b: Rect): Int {
        val full = compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
                .also { hostView.draw(Canvas(it)) }
        }
        val px = Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            .asImageBitmap().toPixelMap()
        var n = 0
        for (x in 0 until px.width) for (y in 0 until px.height) {
            val c = px[x, y]
            if (maxOf(abs(c.red - card.red), abs(c.green - card.green), abs(c.blue - card.blue)) > 0.25f) n++
        }
        return n
    }

    private fun assertRow3Visible(unread: Boolean) {
        show(unread)
        val state = if (unread) "unread" else "read"
        val resumeLabel = app.getString(TextTool.RESUME.label)
        val copyLabel = app.getString(R.string.message_copy_code)
        val chipLabel = app.getString(R.string.a11y_open_attachment, parts[0].name)

        val preview = bounds(email.preview!!, byText = true)
        val resume = bounds(resumeLabel)
        val copy = bounds(copyLabel)
        val bar = bounds("|", byText = true)
        val chip = bounds(chipLabel)

        // Visible, not merely composed: the defect this row shipped twice.
        for ((name, b) in listOf("Resume Mail" to resume, "Copy Code" to copy, "attachment chip" to chip)) {
            val ink = inkedPixels(b)
            assertTrue("#514 $state row: $name at $b draws $ink pixels distinguishable from the card $card — it is invisible", ink >= 20)
        }

        // The THIRD line: under the preview, in #500's order.
        assertTrue("#514 $state row: the icons ($resume) must sit below the preview ($preview)", resume.top >= preview.bottom)
        assertTrue("#500 order: Resume ($resume) left of Copy Code ($copy)", resume.right <= copy.left)
        assertTrue("#500 order: Copy Code ($copy) left of the bar ($bar)", copy.right <= bar.left)
        assertTrue(
            "#500 order: the chip ($chip) follows the bar ($bar) on its line, or wraps under it",
            chip.left >= bar.right || chip.top >= bar.bottom,
        )
        assertTrue("#518: the chip ($chip) must fit inside the window, not be clipped off it", chip.right <= hostView.width)

        compose.onNodeWithContentDescription(chipLabel, useUnmergedTree = true).performClick()
        assertEquals("#196: a chip tap must reach the list's attachment handler with its own part", parts, opened)
    }

    @Test
    fun `514 unread row draws Resume, Copy Code, the bar and the chip where a reader can see them`() =
        assertRow3Visible(unread = true)

    @Test
    fun `514 read row draws Resume, Copy Code, the bar and the chip where a reader can see them`() =
        assertRow3Visible(unread = false)
}
