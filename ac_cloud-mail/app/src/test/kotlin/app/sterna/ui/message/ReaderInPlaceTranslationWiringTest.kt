package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST: the places the in-place translation and the summary box are
 * wired together, which no unit test can reach because they sit inside Compose screens. What this
 * pins is the owner's list: no popup, the toggle beside Unsubscribe and enabled only once a
 * translation exists, the translated fragment entering the document, the summary box under the
 * header, auto-translate gated by detection, and every switch off by default.
 */
class ReaderInPlaceTranslationWiringTest {

    private val screen = source("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
    private val model = source("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
    private val prefs = source("app/src/main/kotlin/app/sterna/ui/text/MailTextToolsPrefs.kt")

    @Test fun `translation opens no popup on the reader`() {
        assertFalse("the dialog is gone from the reader", "TextToolPanel(" in screen)
        assertFalse("the reader no longer runs Translate through the dialog's runner", "textTools.run(" in screen)
        assertTrue(
            "Translate is routed to the in-place pipeline",
            "translate = { viewModel.translateNow(readerBody(email, plainText, derivedNotice, noContent).fragment) }," in screen,
        )
        assertTrue("Resume is routed to the summary box", "summarise = { viewModel.summariseNow(receivedTextToolSource(email)) }," in screen)
    }

    @Test fun `the toggle sits beside Unsubscribe and is enabled only once a translation exists`() {
        val unsub = screen.indexOf("viewModel.askUnsubscribe() },")
        val toggle = screen.indexOf("R.string.message_show_original")
        val headers = screen.indexOf("R.string.message_view_headers")
        assertTrue("Unsubscribe entry not found", unsub > 0)
        assertTrue("the Show Original / Show Translated entry must come right after Unsubscribe", toggle > unsub)
        assertTrue("...and before the next entry (View headers)", toggle < headers)
        val entry = screen.substring(toggle, headers)
        assertTrue("it is disabled until a translation exists", "enabled = translation.exists" in entry)
        assertTrue("it flips the page", "viewModel.toggleTranslated()" in entry)
        assertTrue("it names both states", "R.string.message_show_translated" in entry)
    }

    @Test fun `the translated fragment replaces the message's own inside the document`() {
        assertTrue(
            "ConversationBody is handed the translation only while it is shown and still current",
            "translatedFragment = translation.fragmentFor(readerFragment)," in screen,
        )
        assertTrue("it is a key of the remembered document", "quoteLabel, deceptiveLinkLabel, translatedFragment," in screen)
        assertTrue("the builder swaps the fragment and keeps the rest of the pipeline", "var inner = translatedFragment ?: body.fragment" in screen)
    }

    @Test fun `the summary box is drawn straight after the reading actions, under the header`() {
        val row = screen.indexOf("readingActions()\n")
        val box = screen.indexOf("ResumeBox(LocalReaderSummary.current, msg.id)")
        assertTrue(row > 0 && box > row)
        assertTrue("nothing else is drawn between them", screen.substring(row, box).lines().none { it.trim().startsWith("MessageTagRow") || it.trim().startsWith("PgpStatusCard") })
    }

    @Test fun `a message's state is dropped when the page loads another`() {
        val load = model.substring(model.indexOf("fun load(emailId: String"))
        assertTrue("load() resets the translation and the summary", "resetReaderAi()" in load.take(3000))
        assertTrue("a result for a message the page has left is discarded", "if (loadedId != id) return" in model)
    }

    @Test fun `auto-translate asks the detector first and auto-summary is its own switch`() {
        val open = model.substring(model.indexOf("fun onReaderOpened("), model.indexOf("fun translateNow("))
        assertTrue(
            "auto-translate must be gated by the setting AND by detection, so a message already in the target is left alone",
            "MailTextToolsPrefs.autoTranslate(app) &&\n                        textAi.needsTranslation(htmlToText(fragment), target)" in open,
        )
        assertTrue("auto-summary reads its own switch", "MailTextToolsPrefs.autoSummary(app)" in open)
        assertTrue("a kept translation is shown at once", "ReaderTranslation(target, fragment, kept, shown = true)" in open)
    }

    @Test fun `every new switch is off by default and the languages default to English`() {
        assertTrue(prefs.contains("getBoolean(KEY_AUTO_TRANSLATE, false)"))
        assertTrue(prefs.contains("getBoolean(KEY_AUTO_SUMMARY, false)"))
        assertEquals(1, Regex("""fun summaryLanguage\(context: Context\): String =\s*MailLanguages\.normalise""").findAll(prefs).count())
    }

    @Test fun `the toggle cannot do anything before a translation exists`() {
        val toggle = model.substring(model.indexOf("fun toggleTranslated()"), model.indexOf("fun dismissTranslationError()"))
        assertTrue("if (it.exists) it.copy(shown = !it.shown) else it" in toggle)
    }

    private companion object {
        fun source(path: String): String {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile } ?: error("cannot find $path above ${File("").absolutePath}")
            return File(root, path).readLines().filterNot {
                val s = it.trim(); s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
            }.joinToString("\n")
        }
    }
}
