package com.diegonmarcos.cloudwriter

import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PAGE 2 — "Traducción" / Translation.
 *
 * The same four rows as cloud-keyboard's `TranslationInfoScreen`, under the same
 * "Barra de traducción" heading, with the same wording and the same defaults, stored in
 * cloud-writer's own preference file.
 *
 *   Barra de traducción  (section heading)
 *   1  Idioma de destino predeterminado   list    default "" = Idioma del teclado activo
 *   2  Detectar automáticamente…          switch  default on
 *   3  Intro o acción principal           list    default Insertar en el cursor
 *   4  Escribir directamente en el campo  switch  default on
 *
 * ONLY ROW 1 REACHES THIS APPLICATION'S TRANSLATE BUTTON, and the page says so in
 * `translate_writer_note` rather than leaving it to be found out. Rows 2, 3 and 4 describe what a
 * translate BAR does — detect the source as you type, what Enter does, whether the translation is
 * committed into the app's own field — and cloud-writer has a box and a button, not a bar sitting
 * over someone else's text field. They are stored, they are this application's own, and they are
 * here because the owner asked for the same pages; a row quietly missing is a page that does not
 * match, and a row quietly doing nothing is worse than either.
 *
 * THE WORDING SAYS "TECLADO ACTIVO" AND IS COPIED THAT WAY ON PURPOSE. In cloud-writer that phrase
 * is the keyboard's, not this app's. It is a question for the owner and not a decision to take
 * here: changing it silently is how the pages stopped matching last time.
 */
class TranslationActivity : WriterSettingsActivity() {

    /** The engine's own list once it answers; null until then, and the fallback is drawn meanwhile. */
    @Volatile
    private var engineLanguages: List<String>? = null

    /** ONE for the whole page. A runner per call would be a second binding to the same service. */
    private val runner by lazy { WriterToolRunner(this) }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun pageTitle(): String = getString(R.string.settings_screen_translation)

    override fun buildPage() {
        category(getString(R.string.translate_category_behavior))

        // "" is a real stored value meaning "let the engine decide", and it is the first entry
        // rather than a missing one: a picker whose default is absent cannot show what is set.
        val tags = engineLanguages ?: WriterRegistry.translateFallbackLanguages
        val items = listOf(getString(R.string.translate_default_target_active) to "") +
            tags.sortedBy { Locale(it).displayLanguage }.map { languageName(it) to it }
        listRow(
            getString(R.string.translate_default_target_title),
            null,
            items,
            WriterPrefs.translateTarget(this),
            "",
        ) { WriterPrefs.put(this, WriterPrefs.KEY_TRANSLATE_TARGET, it) }

        switchRow(
            getString(R.string.translate_auto_detect_title),
            getString(R.string.translate_auto_detect_summary),
            WriterPrefs.flag(this, WriterPrefs.KEY_TRANSLATE_AUTO_DETECT, WriterPrefs.DEFAULT_TRANSLATE_AUTO_DETECT),
        ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_TRANSLATE_AUTO_DETECT, it) }

        listRow(
            getString(R.string.translate_apply_mode_title),
            null,
            listOf(
                getString(R.string.translate_apply_insert) to WriterPrefs.APPLY_INSERT,
                getString(R.string.translate_apply_replace) to WriterPrefs.APPLY_REPLACE,
            ),
            WriterPrefs.string(this, WriterPrefs.KEY_TRANSLATE_APPLY_MODE, WriterPrefs.DEFAULT_TRANSLATE_APPLY_MODE),
            WriterPrefs.DEFAULT_TRANSLATE_APPLY_MODE,
        ) { WriterPrefs.put(this, WriterPrefs.KEY_TRANSLATE_APPLY_MODE, it) }

        switchRow(
            getString(R.string.translate_live_commit_title),
            getString(R.string.translate_live_commit_summary),
            WriterPrefs.flag(this, WriterPrefs.KEY_TRANSLATE_LIVE_COMMIT, WriterPrefs.DEFAULT_TRANSLATE_LIVE_COMMIT),
        ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_TRANSLATE_LIVE_COMMIT, it) }

        note(getString(R.string.translate_writer_note))

        askTheEngineForItsLanguages()
    }

    /**
     * Replace the fallback list with the engine's real one.
     *
     * BLOCKS — it is a binder call — so it runs on [worker] and the redraw is posted back. Asked
     * once per page build, and only while the fallback is still what is on screen: a redraw that
     * fired on every build would loop.
     */
    private fun askTheEngineForItsLanguages() {
        if (engineLanguages != null) return
        worker.execute {
            val reported = runner.translateLanguages()
            if (reported.isEmpty()) return@execute
            engineLanguages = reported
            main.post { if (!isFinishing) rebuild() }
        }
    }

    private fun languageName(tag: String): String = Locale(tag).displayLanguage + " (" + tag + ")"
}
