package app.sterna.ui.text

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The defaults the owner asked for, read from a fresh install's preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailTextToolsPrefsDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `a fresh install translates to English and summarises in English`() {
        assertEquals("en", MailTextToolsPrefs.translateTarget(context))
        assertEquals("en", MailTextToolsPrefs.summaryLanguage(context))
    }

    @Test fun `auto-translate and auto-summary are off by default`() {
        assertFalse(MailTextToolsPrefs.autoTranslate(context))
        assertFalse(MailTextToolsPrefs.autoSummary(context))
    }

    @Test fun `the switches and the languages are stored`() {
        MailTextToolsPrefs.putBoolean(context, MailTextToolsPrefs.KEY_AUTO_TRANSLATE, true)
        MailTextToolsPrefs.putBoolean(context, MailTextToolsPrefs.KEY_AUTO_SUMMARY, true)
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_TRANSLATE_TARGET, "de")
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_SUMMARY_LANGUAGE, "es")
        assertTrue(MailTextToolsPrefs.autoTranslate(context))
        assertTrue(MailTextToolsPrefs.autoSummary(context))
        assertEquals("de", MailTextToolsPrefs.translateTarget(context))
        assertEquals("es", MailTextToolsPrefs.summaryLanguage(context))
    }

    @Test fun `the summary prompt is told which language to write in`() {
        assertTrue(MailTextToolsPrefs.summaryPrompt(context, "es").endsWith("Write the summary in Spanish."))
        assertTrue(MailTextToolsPrefs.summaryPrompt(context).contains("Write the summary in English."))
    }

    @Test fun `an old free-text target that is blank reads as English`() {
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_TRANSLATE_TARGET, "")
        assertEquals("en", MailTextToolsPrefs.translateTarget(context))
    }

    @Test fun `the answer prompt defaults to the sensible one and states its rules`() {
        val p = MailTextToolsPrefs.answerPrompt(context)
        assertEquals(AnswerPrompt.DEFAULT, p)
        assertTrue(p.contains("language of the message"))
        assertTrue(p.contains("tone"))
        assertTrue(p.contains("concise"))
        assertTrue(p.contains("Never invent"))
        assertTrue(p.contains("[date]"))
    }

    @Test fun `an edited answer prompt is stored, a blank one reads as the default, and reset restores it`() {
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_ANSWER_PROMPT, "Be brief.")
        assertEquals("Be brief.", MailTextToolsPrefs.answerPrompt(context))
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_ANSWER_PROMPT, "  ")
        assertEquals(AnswerPrompt.DEFAULT, MailTextToolsPrefs.answerPrompt(context))
        MailTextToolsPrefs.put(context, MailTextToolsPrefs.KEY_ANSWER_PROMPT, "Be brief.")
        MailTextToolsPrefs.resetAnswerPrompt(context)
        assertEquals(AnswerPrompt.DEFAULT, MailTextToolsPrefs.answerPrompt(context))
    }
}
