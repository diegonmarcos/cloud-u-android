package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The account screen shares one form between two ways of writing: four sections (colour,
 * notifications, sync window, PGP) are written the instant they are tapped, while the typed fields
 * wait for Save because they have to be validated first. The button is `enabled = canSave && dirty`
 * (#34), so after an autosaved tap it stays grey — correctly, there is nothing left to write — and
 * on a screen carrying a Save button a grey button reads as a refusal. Nothing on the screen said
 * which fields the button owns. `settings_save_scope` is that sentence.
 *
 * Three rules are held here, and they fail for different reasons.
 *
 * 1. SOURCE LINT (not a behaviour test): the caption is rendered, above the button, in the same
 *    block as the button. Compose is not renderable in this module — no Robolectric, no
 *    instrumented tests — so reading the source is what is left. It proves nothing about pixels.
 * It compares WHOLE LINES, never `"fragment" in line`: a containment check is blind to every
 *    mutation that makes the line longer, which is most of them (adding a `modifier =`, wrapping
 *    the call in a condition, swapping the style for a louder one).
 *
 * 2. THE WORDING DECISION, executed. The caption must NOT say the other settings take effect
 *    immediately, because they do not: `AccountsViewModel.setSyncWindow` writes the value and calls
 *    `refresh()`, which re-reads `currentId` and nothing else — no sync is restarted. Saying
 *    "applies immediately" would replace a mute screen with a false one, on the very row that
 *    produced the report. That decision is [promisesImmediateEffect], a pure function, and it is
 *    RUN twice: once on every shipped caption (must say no) and once on a witness sentence per
 *    language that does promise immediacy (must say yes). Without the witness half, a rule that
 *    answers "no" to everything would be green for the wrong reason.
 *
 * 3. THE POSITIVE ANCHORS. A ban list can only forbid, and seven of the nine captions had nothing
 *    else holding them: an audit reversed the Dutch second sentence into its exact opposite ("are
 *    only saved when you press Save") and the whole file stayed green, because the reversal
 *    contains none of the forbidden words. So each caption must also SAY three things, checked
 *    against the same locale's own resources — its own Save label, its own Identities section
 *    name — plus two sentences and a connective meaning "as soon as you change them". Only the
 *    connective list is written per language here; the other two anchors hold nine translations
 *    without this file containing any of them.
 *
 * What is NOT covered: that any translation means what it says. No test reads a sentence for
 * meaning — a caption can satisfy every anchor and still be clumsy or subtly wrong. The safety net
 * against the enumeration going stale as `save()` grows is the comment on that signature, not this
 * file. And the caption explains only the grey caused by "nothing to write" (`!dirty`), never the
 * grey caused by an invalid form (`!canSave`, `AccountEditorSave-70`); that is known and out
 * of scope.
 */
class SaveScopeCaptionTest {

    // -- 1. the caption is on the screen, above the button, in the button's own block ----------

    @Test fun `the caption is rendered exactly once, in the style this screen uses for notes`() {
        val lines = codeLines()
        val call = callAt(lines, captionArgIndex(lines).let { enclosingIndex(lines, it) })
        assertEquals(
            "the save-scope caption must be a plain Text in the column's own style. bodySmall + " +
                "onSurfaceVariant is the muted form, so that a caption which is ALWAYS there does " +
                "not shout over the conditional \"Unsaved changes\" cue just above it; and it must " +
                "carry NO modifier — the enclosing Column already gives it the 16 dp side padding " +
                "and the 8 dp gap, and a second padding would step the caption out of the column.",
            EXPECTED_CALL, call,
        )
    }

    @Test fun `the caption sits above the Save button, not below it`() {
        val lines = codeLines()
        val caption = enclosingIndex(lines, captionArgIndex(lines))
        val button = enclosingIndex(lines, saveGuardIndex(lines))
        assertTrue(
            "the caption must come BEFORE the Save button. Below it, the gap to \"Sign out\" is the " +
                "same 8 dp and the sentence reads as qualifying the sign-out instead. Caption line " +
                "${caption + 1}, button line ${button + 1} (of the comment-stripped file).",
            caption < button,
        )
    }

    @Test fun `the caption lives in the same block as the button, unconditionally`() {
        val lines = codeLines()
        val captionBlock = enclosingIndex(lines, enclosingIndex(lines, captionArgIndex(lines)))
        val buttonBlock = enclosingIndex(lines, enclosingIndex(lines, saveGuardIndex(lines)))
        assertEquals(
            "the caption must open in the SAME block as the Save button: that block is the one the " +
                "screen skips whole when the account has been deleted under it, which is how the " +
                "caption disappears with the button without a condition of its own. Caption's " +
                "enclosing line:\n${lines[captionBlock]}\nButton's enclosing line:\n${lines[buttonBlock]}",
            buttonBlock, captionBlock,
        )
        val opener = lines[captionBlock]
        assertTrue(
            "the block holding the caption and the Save button must not be a condition — a caption " +
                "shown only sometimes is worse than none, since its absence says nothing. Opening " +
                "line was:\n$opener",
            "if (" !in opener && "when (" !in opener,
        )
    }

    // -- 2. the decision: the caption never promises the setting takes effect ------------------

    @Test fun `the immediacy rule flags a sentence that does promise immediate effect`() {
        // The rule is executed here on a known-bad input in every language. A ban list that matches
        // nothing would let the real check below pass for the wrong reason, and the whole value of
        // this caption is the one claim it must not make.
        val missed = WITNESS.filterNot { (locale, sentence) -> promisesImmediateEffect(locale, sentence) }
        assertEquals(
            "the immediacy rule said nothing about a sentence that plainly promises immediate " +
                "effect — it cannot protect the shipped captions either",
            emptyMap<String, String>(), missed,
        )
    }

    @Test fun `no language promises the other settings take effect immediately`() {
        val lying = captions().filter { (locale, text) -> promisesImmediateEffect(locale, text) }
        assertEquals(
            "the caption says the other settings are SAVED as you change them, never that they are " +
                "APPLIED: setSyncWindow writes the value and calls refresh(), which re-reads the " +
                "current account id and nothing else. Nothing re-syncs. A caption promising " +
                "immediacy turns a screen that says nothing into a screen that lies, on the row " +
                "the report was about.",
            emptyMap<String, String>(), lying,
        )
    }

    @Test fun `the English and French captions still name what Save writes`() {
        // Pinned in full for the two reference locales, because the enumeration is the substance:
        // the username and the password/API token are written by Save and are the fields whose
        // silent loss breaks the account. Softening this to "saves your changes" must cost a
        // deliberate edit here.
        val captions = captions()
        assertEquals("the English caption changed", ENGLISH, captions["values"])
        assertEquals("the French caption changed", FRENCH, captions["values-fr"])
    }

    // -- 3. the positive anchors: what every translation must still SAY -------------------------

    @Test fun `every language names the button that writes, and the identities it writes`() {
        val broken = anchorFailures().mapValues { (_, missing) ->
            missing.filter { it != SAYS_SAVED_ON_CHANGE }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "a ban list can only forbid; it cannot notice a translation that says nothing. These " +
                "are the two things every caption must still contain, taken from the SAME locale " +
                "file so that no translation is hardcoded here: the label of its own Save button " +
                "(R.string.settings_save — the caption is 8 dp from three other controls and a " +
                "sentence that opens on a subjectless verb attaches to whichever one the reader " +
                "picks), and the name of its own Identities section " +
                "(R.string.settings_identities_section — the enumeration is the substance, and a " +
                "caption softened to \"saves your changes\" is useless). Plus two sentences, " +
                "because the second one is the whole point and deleting it is silent.",
            emptyMap<String, List<String>>(), broken,
        )
    }

    @Test fun `every language says the other settings are saved as they change`() {
        val broken = anchorFailures().filterValues { SAYS_SAVED_ON_CHANGE in it }
        assertEquals(
            "the second sentence must say the other settings are saved AS YOU CHANGE THEM. Without " +
                "this anchor a translation can state the exact opposite — \"are only saved when you " +
                "press Save\" — and every negative rule stays silent, because the reversed sentence " +
                "contains none of the forbidden words. That is the report's own row, read backwards, " +
                "in one language.",
            emptyMap<String, List<String>>(), broken,
        )
    }

    @Test fun `the anchors catch a translation that says the opposite, or drops the point`() {
        // Run on inputs written to fail, not derived from the rule: the Dutch caption reversed
        // ("are only saved when you press Save"), and the same caption with its second sentence
        // deleted. Both keep every word the ban lists look for; only the anchors can see them.
        val head = "De knop ‘Opslaan’ slaat de accountnaam, de serverinstellingen, de inloggegevens " +
            "en de identiteiten op."
        assertEquals(
            "the reversed Dutch caption must fail on the second-sentence anchor and on nothing else",
            listOf(SAYS_SAVED_ON_CHANGE),
            missingAnchors(
                "values-nl",
                "$head De overige instellingen op dit scherm worden pas opgeslagen als je op Opslaan drukt.",
                saveLabel = "Opslaan", identitiesLabel = "Identiteiten",
            ),
        )
        assertEquals(
            "a caption whose second sentence was simply deleted must fail on both",
            listOf(TWO_SENTENCES, SAYS_SAVED_ON_CHANGE),
            missingAnchors("values-nl", head, saveLabel = "Opslaan", identitiesLabel = "Identiteiten"),
        )
        assertEquals(
            "a caption watered down to \"saves your changes\" must fail on the identities anchor",
            listOf(NAMES_IDENTITIES),
            missingAnchors(
                "values-nl",
                "De knop ‘Opslaan’ slaat je wijzigingen op. De overige instellingen op dit scherm " +
                    "worden opgeslagen zodra je ze wijzigt.",
                saveLabel = "Opslaan", identitiesLabel = "Identiteiten",
            ),
        )
    }

    @Test fun `every language ships the caption`() {
        val files = stringFiles()
        assertTrue(
            "only ${files.size} locale string files found; the app ships at least nine, so this " +
                "rule is no longer reading them all",
            files.size >= 9,
        )
        val captions = captions()
        val silent = files.map { it.parentFile.name }.filterNot { it in captions }
        assertEquals(
            "a locale without settings_save_scope shows the English sentence under a translated " +
                "Save button, which is exactly the half-translation the screen cannot afford here",
            emptyList<String>(), silent,
        )
    }

    // -- the decision, as a function ------------------------------------------------------------

    /**
     * Does [caption] promise that the settings it talks about take effect, rather than merely being
     */
    private fun promisesImmediateEffect(locale: String, caption: String): Boolean {
        val banned = IMMEDIACY[locale] ?: error(
            "no immediacy vocabulary for $locale — a tenth language cannot be checked by a list " +
                "written for nine, and passing it silently would be the failure this rule exists for",
        )
        val text = caption.lowercase()
        return banned.any { it in text }
    }

    /**
     * Which of the four things a caption must say are missing from [caption], in a fixed order.
     */
    private fun missingAnchors(
        locale: String,
        caption: String,
        saveLabel: String,
        identitiesLabel: String,
    ): List<String> {
        val text = caption.lowercase()
        val connectives = AS_SOON_AS[locale] ?: error(
            "no \"as soon as\" vocabulary for $locale — a tenth language cannot be checked by a " +
                "list written for nine",
        )
        val buttonWords = BUTTON[locale] ?: error(
            "no word for \"button\" for $locale — a tenth language cannot be checked by a list " +
                "written for nine",
        )
        return buildList {
            // BOTH the label and the word "button": in English and Italian the Save label IS the
            // bare verb, so "Saves the account name…" / "Salva il nome…" contains it by accident
            if (saveLabel.lowercase() !in text || buttonWords.none { it in text }) add(NAMES_SAVE_BUTTON)
            if (identitiesLabel.lowercase() !in text) add(NAMES_IDENTITIES)
            if (SENTENCE_END.findAll(caption).count() < 2) add(TWO_SENTENCES)
            if (connectives.none { it in text }) add(SAYS_SAVED_ON_CHANGE)
        }
    }

    /** Every locale that ships a caption, with the anchors it fails. */
    private fun anchorFailures(): Map<String, List<String>> = stringFiles()
        .mapNotNull { file ->
            val locale = file.parentFile.name
            val body = file.readText()
            val caption = CAPTION.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val save = SAVE_LABEL.find(body)?.groupValues?.get(1)
                ?: error("$locale ships settings_save_scope but no settings_save to anchor it to")
            val identities = IDENTITIES_LABEL.find(body)?.groupValues?.get(1)
                ?: error("$locale ships settings_save_scope but no settings_identities_section")
            missingAnchors(locale, caption, save, identities).takeIf { it.isNotEmpty() }
                ?.let { locale to it }
        }
        .toMap()

    // -- reading the files ----------------------------------------------------------------------

    /** locale directory name -> the caption it ships, for the locales that ship one. */
    private fun captions(): Map<String, String> = stringFiles()
        .mapNotNull { file ->
            CAPTION.find(file.readText())?.groupValues?.get(1)?.let { file.parentFile.name to it }
        }
        .toMap()

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    /** As in the sibling source lints: a line that opens with a comment marker is dropped whole. */
    private fun codeLines(): List<String> = SETTINGS_SCREEN.readLines().filterNot {
        val code = it.trimStart()
        code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
    }

    /** The one line that is the caption's argument, matched WHOLE. */
    private fun captionArgIndex(lines: List<String>): Int = onlyLine(lines, CAPTION_ARG)

    /** The one line that is the Save button's guard, matched WHOLE — the button's unique marker. */
    private fun saveGuardIndex(lines: List<String>): Int = onlyLine(lines, SAVE_GUARD)

    private fun onlyLine(lines: List<String>, whole: String): Int {
        val hits = lines.indices.filter { lines[it].trim() == whole }
        check(hits.size == 1) {
            "expected exactly one line reading `$whole` in SettingsScreen.kt, found ${hits.size}. " +
                "Either it was edited (this rule must be moved with it) or it now appears twice, " +
                "in which case this rule is reading the wrong one."
        }
        return hits.single()
    }

    /** The nearest preceding code line indented LESS than [i] — the line that opens its block. */
    private fun enclosingIndex(lines: List<String>, i: Int): Int {
        val indent = lines[i].indentWidth()
        return (i - 1 downTo 0)
            .firstOrNull { lines[it].isNotBlank() && lines[it].indentWidth() < indent }
            ?: error("line ${i + 1} of SettingsScreen.kt is at the top level — has the file moved?")
    }

    /** A call starting at [start], as trimmed lines, down to the line that closes it at its indent. */
    private fun callAt(lines: List<String>, start: Int): List<String> {
        val indent = lines[start].indentWidth()
        val out = mutableListOf(lines[start].trim())
        for (i in start + 1 until lines.size) {
            if (lines[i].isBlank()) continue
            out += lines[i].trim()
            if (lines[i].indentWidth() <= indent) break
        }
        return out
    }

    private fun String.indentWidth() = length - trimStart().length

    private companion object {
        const val CAPTION_ARG = "stringResource(R.string.settings_save_scope),"
        const val SAVE_GUARD = "enabled = canSave && dirty,"

        /** Whole lines, in order — not fragments, so that anything ADDED to the call fails too. */
        val EXPECTED_CALL = listOf(
            "Text(",
            CAPTION_ARG,
            "style = MaterialTheme.typography.bodySmall,",
            "color = MaterialTheme.colorScheme.onSurfaceVariant,",
            ")",
        )

        const val ENGLISH =
            "The “Save” button writes the account name, server settings, credentials and " +
                "identities. The other settings on this screen are saved as soon as you change them."

        const val FRENCH =
            "Le bouton « Enregistrer » écrit le nom du compte, les réglages du serveur, les " +
                "identifiants et les identités. Les autres réglages de cet écran sont enregistrés " +
                "dès que vous les changez."

        const val NAMES_SAVE_BUTTON = "does not name its own Save button"
        const val NAMES_IDENTITIES = "does not name its own Identities section"
        const val TWO_SENTENCES = "is not two sentences"
        const val SAYS_SAVED_ON_CHANGE = "does not say the rest is saved as you change it"

        /**
         * Ways each language says "takes effect now" — the one thing this caption must not say.
         */
        val IMMEDIACY = mapOf(
            "values" to listOf("immediat", "appl", "take effect", "takes effect", "taking effect", "at once", "instant", "right away", "straight away", "in effect"),
            "values-fr" to listOf("immédiat", "appliqu", "prend effet", "prennent effet", "aussitôt", "sur-le-champ", "en vigueur"),
            "values-de" to listOf("sofort", "unmittelbar", "wirk", "anwend", "angewend", "übernomm", "greif", "gelten", "gilt", "in kraft"),
            "values-es" to listOf("inmediat", "aplic", "surte", "al instante", "vigor"),
            "values-it" to listOf("immediat", "subito", "applic", "effetto", "istantane", "in vigore"),
            "values-nl" to listOf("onmiddellijk", "meteen", "direct", "van kracht", "toepass", "toegepast", "gelden", "geldt", "werking"),
            "values-pl" to listOf("natychmiast", "zaraz", "od razu", "stosowan", "stosuj", "obowiąz", "wchodzą w życie", "w życie"),
            "values-pt" to listOf("imediat", "aplic", "surte", "no ato", "vigor"),
            "values-ru" to listOf("сразу", "немедленн", "мгновенн", "примен", "вступа", "в силу", "действу"),
        )

        /**
         * WRITTEN BEFORE [IMMEDIACY] WAS TOUCHED, and deliberately not from it: these are the
         */
        val WITNESS = mapOf(
            "values" to "The other settings on this screen take effect straight away.",
            "values-fr" to "Les autres réglages de cet écran prennent effet sur-le-champ.",
            "values-de" to "Die übrigen Einstellungen gelten ab dem Moment der Änderung.",
            "values-es" to "Los demás ajustes entran en vigor al momento.",
            "values-it" to "Le altre impostazioni hanno effetto subito.",
            "values-nl" to "De overige instellingen zijn meteen van kracht.",
            "values-pl" to "Pozostałe ustawienia zaczynają obowiązywać od zaraz.",
            "values-pt" to "As outras configurações entram em vigor de imediato.",
            "values-ru" to "Остальные настройки вступают в силу сразу же.",
        )

        /** How each language says "button" — the caption has to name the thing it describes. */
        val BUTTON = mapOf(
            "values" to listOf("button"),
            "values-fr" to listOf("bouton"),
            "values-de" to listOf("schaltfläche", "knopf", "button"),
            "values-es" to listOf("botón"),
            "values-it" to listOf("pulsante"),
            "values-nl" to listOf("knop"),
            "values-pl" to listOf("przycisk"),
            "values-pt" to listOf("botão"),
            "values-ru" to listOf("кнопк"),
        )

        /** How each language says "as soon as you change them" — the claim, not the wording. */
        val AS_SOON_AS = mapOf(
            "values" to listOf("as soon as", "the moment"),
            "values-fr" to listOf("dès que", "dès lors que", "au moment où"),
            "values-de" to listOf("sobald", "in dem moment"),
            "values-es" to listOf("en cuanto", "tan pronto como", "nada más"),
            "values-it" to listOf("appena", "nel momento in cui"),
            "values-nl" to listOf("zodra", "op het moment dat"),
            "values-pl" to listOf("w chwili", "z chwilą", "gdy tylko", "kiedy tylko"),
            "values-pt" to listOf("assim que", "logo que", "no momento em que"),
            "values-ru" to listOf("как только", "в момент"),
        )

        /** A sentence end: the caption must have two of them, because the second one is the point. */
        val SENTENCE_END = Regex("[.!?](\\s|\$)")

        val CAPTION = Regex("<string name=\"settings_save_scope\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val SAVE_LABEL = Regex("<string name=\"settings_save\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val IDENTITIES_LABEL = Regex("<string name=\"settings_identities_section\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        const val RES = "app/src/main/res"
        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources and resources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
    }
}
