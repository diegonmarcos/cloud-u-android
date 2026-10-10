// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autofill

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.diegonmarcos.superapp.autofill.AutofillCandidates
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.Snippet

/**
 * Tier 3 of the fleet autofill, pure half (a0_docs/eng-specs/autofill-3-tier.md).
 *
 * [FieldPolicy] reads what the editor says about the focused field and decides the keyboard's
 * mode: SUPPRESSED for any password variation, a one-time code, a card number, or an app that
 * asks for no personalised learning — the keyboard then shows none of its own chips, records
 * nothing and learns nothing — else NORMAL, with the Cloud Account SOT key the field wants
 * (email → emails, tel → phones, postal → postal codes, a name…) when it can tell.
 *
 * [SuggestionRows] is the owner's two-row arbitration: row 1 holds ONLY Cloud Vault / Android inline
 * autofill suggestions and exists only while there are some; row 2 is the keyboard's own row
 * (SOT candidates, snippets, word suggestions) and is hidden in SUPPRESSED mode. The two never mix.
 */
enum class FieldMode { NORMAL, SUPPRESSED }

data class FieldDecision(
    val mode: FieldMode,
    /** Why it is suppressed (password | otp | card | id | no_learning), or "" when NORMAL. */
    val reason: String = "",
    /** The SOT field key this field takes (WHATWG token), or null when the keyboard cannot tell. */
    val key: String? = null,
    /** True for a multi-line text field (TYPE_TEXT_FLAG_MULTI_LINE): the user's snippets are offered there. */
    val snippets: Boolean = false,
) {
    val suppressed get() = mode == FieldMode.SUPPRESSED
}

object FieldPolicy {
    private val OTP = Regex("one.?time|\\botp\\b|2fa|totp|sms.?code|verification.?code|security.?code|auth(entication)?.?code|" +
        "c[oó]digo de verifica|code de v[ée]rification|best[äa]tigungscode|sicherheitscode|einmal", RegexOption.IGNORE_CASE)
    private val CARD = Regex("card.?number|credit.?card|\\bcvv|\\bcvc|\\bcsc\\b|cc.?(number|csc|exp)|kartennummer|n[uú]mero de (la )?tarjeta|num[ée]ro de carte|\\biban\\b", RegexOption.IGNORE_CASE)
    /** Identity documents (DNI/NIE, Personalausweis, passport, CPF/RG…): Cloud Vault's Identity items, never our row. */
    private val ID_DOC = Regex("\\bdni\\b|\\bnie\\b|\\bnif\\b|personalausweis|ausweis|passport|pasaporte|passaporte|reisepass|\\bcpf\\b|\\brg\\b|" +
        "residence.?permit|aufenthaltstitel|id.?number|national.?id|document.?number|n[uú]mero de documento|\\bssn\\b|tax.?id|steuer.?id", RegexOption.IGNORE_CASE)
    private val PASSWORD = Regex("pass(word|wort)?\\b|passwd|\\bpwd\\b|contrase[nñ]a|senha|mot de passe|kennwort|\\bpin\\b", RegexOption.IGNORE_CASE)
    /** Autofill hint spellings the framework and Chromium use (View.AUTOFILL_HINT_*, HintConstants, WHATWG). */
    private val SECRET_HINTS = setOf("password", "newpassword", "current-password", "new-password", "one-time-code", "smsotpcode",
        "2faappotpcode", "smsotpcodedigit", "creditcardnumber", "creditcardsecuritycode", "creditcardexpirationdate", "cc-number", "cc-csc", "cc-exp")

    private val WORDS = listOf(
        "email" to Regex("e.?mail|correo|courriel", RegexOption.IGNORE_CASE),
        "postal-code" to Regex("zip|postal|postcode|\\bplz\\b|postleitzahl|\\bcep\\b|c[oó]digo postal|code postal", RegexOption.IGNORE_CASE),
        "tel" to Regex("phone|\\btel|mobile|telefon|tel[eé]fono|telefone|celular|handy", RegexOption.IGNORE_CASE),
        "given-name" to Regex("first.?name|given.?name|vorname|pr[eé]nom|primeiro nome", RegexOption.IGNORE_CASE),
        "family-name" to Regex("last.?name|surname|family.?name|nachname|apellido|sobrenome", RegexOption.IGNORE_CASE),
        "organization" to Regex("company|organi[sz]ation|firma|empresa|entreprise", RegexOption.IGNORE_CASE),
        "address-level2" to Regex("\\bcity|\\btown|\\bstadt|ciudad|cidade|ville", RegexOption.IGNORE_CASE),
        "address-line1" to Regex("street|address|stra(ss|ß)e|calle|direcci[oó]n|endere[cç]o|adresse", RegexOption.IGNORE_CASE),
        "country" to Regex("country|\\bland\\b|pa[ií]s|\\bpays\\b", RegexOption.IGNORE_CASE),
        "name" to Regex("full.?name|your.?name|^name$|\\bname\\b|\\bnombre\\b|\\bnome\\b|\\bnom\\b", RegexOption.IGNORE_CASE),
    )

    /**
     * The decision for a field. [inputType]/[imeOptions] as EditorInfo carries them; [texts] are the
     * hint text, field name and any autofill hints the editor passed (EditorInfo.extras). Pure.
     */
    fun decide(inputType: Int, imeOptions: Int, texts: List<String> = emptyList()): FieldDecision {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val joined = texts.filter { it.isNotBlank() }.joinToString(" ")
        val hints = texts.map { it.lowercase().replace("_", "").replace(" ", "") }
        val passwordType = when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        when {
            passwordType -> return FieldDecision(FieldMode.SUPPRESSED, "password")
            hints.any { it in SECRET_HINTS && (it.contains("otp") || it.contains("one-time")) } || OTP.containsMatchIn(joined) ->
                return FieldDecision(FieldMode.SUPPRESSED, "otp")
            hints.any { it in SECRET_HINTS && (it.contains("credit") || it.startsWith("cc-")) } || CARD.containsMatchIn(joined) ->
                return FieldDecision(FieldMode.SUPPRESSED, "card")
            hints.any { it in SECRET_HINTS } || PASSWORD.containsMatchIn(joined) -> return FieldDecision(FieldMode.SUPPRESSED, "password")
            ID_DOC.containsMatchIn(joined) -> return FieldDecision(FieldMode.SUPPRESSED, "id")
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 -> return FieldDecision(FieldMode.SUPPRESSED, "no_learning")
        }
        val key = when {
            cls == InputType.TYPE_CLASS_PHONE -> "tel"
            cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> "email"
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "address-line1"
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "name"
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> null
            else -> WORDS.firstOrNull { it.second.containsMatchIn(joined) }?.first
        }
        val multiLine = cls == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return FieldDecision(FieldMode.NORMAL, key = key, snippets = multiLine)
    }

    /** The texts an EditorInfo carries about its field: hint, field name, and string extras that look like autofill hints. */
    fun texts(info: EditorInfo?): List<String> {
        if (info == null) return emptyList()
        val out = ArrayList<String>()
        info.hintText?.toString()?.let(out::add)
        info.fieldName?.let(out::add)
        info.label?.toString()?.let(out::add)
        runCatching {
            val ex = info.extras ?: return@runCatching
            for (k in ex.keySet()) {
                if (!k.contains("hint", true) && !k.contains("autofill", true) && !k.contains("autocomplete", true)) continue
                @Suppress("DEPRECATION") when (val v = ex.get(k)) {
                    is String -> out += v
                    is Array<*> -> v.filterIsInstance<String>().forEach(out::add)
                    is java.util.ArrayList<*> -> v.filterIsInstance<String>().forEach(out::add)
                }
            }
        }
        return out
    }

    fun decide(info: EditorInfo?): FieldDecision =
        if (info == null) FieldDecision(FieldMode.NORMAL) else decide(info.inputType, info.imeOptions, texts(info))
}

/** What each suggestion row shows. Row 1 = Vault/inline only; row 2 = the keyboard's own. */
data class RowState(val row1: Boolean, val row2: Boolean, val row2Sot: Boolean)

object SuggestionRows {
    /**
     * [inline] = how many inline autofill suggestions the framework handed over; [own] = how many
     * SOT/snippet candidates the keyboard has for this field. Pure.
     */
    fun decide(inline: Int, mode: FieldMode, own: Int): RowState = RowState(
        row1 = inline > 0,
        row2 = mode == FieldMode.NORMAL,
        row2Sot = mode == FieldMode.NORMAL && own > 0,
    )
}

/** Row 2's SOT part for one field: the profiles that have a value (picker), the active one's values, snippets. */
data class ImeRow(val profiles: List<String>, val active: Int, val values: List<String>, val snippets: List<Snippet>) {
    /** More than one profile has something: the row starts with a "<profile> ▾" chip that switches. */
    val switcher get() = profiles.size > 1
    val isEmpty get() = values.isEmpty() && snippets.isEmpty()
    companion object { val EMPTY = ImeRow(emptyList(), 0, emptyList(), emptyList()) }
}

/** The keyboard's own candidates for a field (row 2): SOT values for its key per profile, then snippets. Pure. */
object ImeCandidates {
    const val MAX = 8

    fun row(d: FieldDecision, profiles: List<AutofillProfile>, snippets: List<Snippet>, active: Int = 0, typed: String = ""): ImeRow {
        if (d.suppressed) return ImeRow.EMPTY
        val t = typed.trim()
        fun fit(v: List<String>) = v.filter { t.isEmpty() || it.startsWith(t, ignoreCase = true) && !it.equals(t, ignoreCase = true) }.distinct().take(MAX)
        val per = d.key?.let { k -> profiles.sortedByDescending { it.isDefault }.map { it.title to AutofillCandidates.forKey(k, listOf(it)) }.filter { it.second.isNotEmpty() } }.orEmpty()
        val i = if (per.isEmpty()) 0 else Math.floorMod(active, per.size)
        val snips = if (d.snippets) snippets.filter { it.text.isNotBlank() }.take(MAX) else emptyList()
        return ImeRow(per.map { it.first }, i, per.getOrNull(i)?.second?.let(::fit).orEmpty(), snips)
    }

    /** Flat list (values then snippet texts) — what row 2 commits, in order. */
    fun build(d: FieldDecision, profiles: List<AutofillProfile>, snippets: List<Snippet>, typed: String = ""): List<String> =
        row(d, profiles, snippets, 0, typed).let { it.values + it.snippets.map { s -> s.text } }
}
