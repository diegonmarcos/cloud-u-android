package app.sterna.core.data.text

/**
 * A small, offline guess at what language a text is written in, for "is this message already in my
 * language?" - nothing more.
 *
 * WHY NOT ML KIT. The fleet keeps ML Kit (translate, language-id) in the ENGINE apk behind the
 * translate engine, and mail reaches text tools only over the ITextTools binder, which has no detect
 * call. Bundling the engine library into the mail apk would put an engine in a second place; adding a
 * binder method would re-ship every app that links the contract. So this is a heuristic that answers
 * in one direction only: it says a language when it is sure, and `null` ("und") when it is not. The
 * caller treats null as "do not translate", so a wrong guess cannot cost more than one tap.
 *
 * HOW: a script test first (Cyrillic, Greek, Arabic, Hebrew, Hangul, kana, Han, Thai, Devanagari are
 * decisive by themselves), then, for Latin text, the share of the words that are among a language's
 * most frequent function words. The best language must clear [MIN_SHARE] AND beat the runner-up by
 * [MARGIN], which is what keeps close relatives (es/pt, no/sv, nl/de) from being called on a few
 * words.
 */
object LanguageGuess : LanguageDetector {
    const val MIN_SHARE = 0.12
    const val MARGIN = 1.4

    override suspend fun detect(text: String): String? = guess(text)

    fun guess(text: String): String? {
        var letters = 0
        val scripts = HashMap<String, Int>()
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            val key = when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.CYRILLIC -> "ru"
                Character.UnicodeScript.GREEK -> "el"
                Character.UnicodeScript.ARABIC -> "ar"
                Character.UnicodeScript.HEBREW -> "he"
                Character.UnicodeScript.HANGUL -> "ko"
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
                Character.UnicodeScript.HAN -> "zh"
                Character.UnicodeScript.THAI -> "th"
                Character.UnicodeScript.DEVANAGARI -> "hi"
                Character.UnicodeScript.LATIN -> "latin"
                else -> "other"
            }
            scripts.merge(key, 1, Int::plus)
        }
        if (letters == 0) return null
        val top = scripts.maxByOrNull { it.value } ?: return null
        if (top.value * 2 < letters) return null
        // Kana inside Han text means Japanese, not Chinese.
        if (top.key == "zh" && (scripts["ja"] ?: 0) > 0) return "ja"
        if (top.key == "ja" || top.key != "latin") return top.key.takeIf { it != "other" }
        return latin(text)
    }

    private fun latin(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.length in 1..14 }
        if (words.size < 4) return null
        val scored = STOPWORDS.map { (lang, list) ->
            lang to words.count { it in list }.toDouble() / words.size
        }.sortedByDescending { it.second }
        val (best, share) = scored[0]
        val runnerUp = scored[1].second
        if (share < MIN_SHARE) return null
        if (runnerUp > 0 && share < runnerUp * MARGIN) return null
        return best
    }

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "of", "to", "in", "is", "you", "that", "it", "for", "on", "with", "as", "are", "this", "be", "at", "your", "have", "from", "or", "by", "we", "will", "not", "can", "an", "if", "our", "has"),
        "es" to setOf("el", "la", "de", "que", "y", "en", "los", "las", "del", "se", "por", "un", "una", "con", "para", "es", "no", "su", "al", "lo", "como", "más", "pero", "sus", "le", "ya", "este", "si", "muy", "gracias"),
        "fr" to setOf("le", "la", "les", "de", "des", "du", "et", "en", "un", "une", "que", "est", "pour", "dans", "qui", "sur", "pas", "au", "avec", "vous", "nous", "ce", "il", "ne", "se", "votre", "sont", "par", "plus", "ou"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "mit", "von", "sie", "auf", "für", "im", "dem", "sich", "auch", "wir", "es", "ich", "bei", "dass", "oder", "wird", "aus", "nach", "haben", "ihre"),
        "pt" to setOf("o", "a", "de", "que", "e", "do", "da", "em", "um", "uma", "para", "com", "não", "os", "as", "dos", "no", "na", "por", "mais", "se", "é", "seu", "sua", "ao", "como", "mas", "foi", "você", "obrigado"),
        "it" to setOf("il", "lo", "la", "di", "che", "e", "in", "un", "una", "per", "con", "non", "del", "della", "le", "i", "si", "da", "sono", "al", "più", "ma", "come", "anche", "suo", "gli", "dei", "nel", "alla", "grazie"),
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "op", "te", "in", "voor", "met", "niet", "zijn", "er", "aan", "ook", "als", "om", "bij", "naar", "je", "uw", "wij", "worden", "maar", "dit", "deze", "door", "kunt"),
        "sv" to setOf("och", "att", "det", "är", "som", "en", "på", "för", "med", "av", "inte", "den", "till", "har", "ett", "om", "ni", "vi", "kan", "du", "från", "så", "men", "ska", "eller", "vår", "din", "denna", "vill", "mycket"),
        "pl" to setOf("i", "w", "na", "z", "do", "nie", "się", "że", "to", "jest", "o", "jak", "po", "co", "dla", "ale", "od", "za", "przez", "czy", "tak", "pan", "pani", "oraz", "który", "są", "tylko", "przy", "już", "dziękujemy"),
        "tr" to setOf("ve", "bir", "bu", "için", "ile", "de", "da", "çok", "gibi", "daha", "olarak", "en", "ne", "ama", "var", "mi", "ben", "siz", "biz", "olan", "kadar", "sonra", "her", "değil", "veya", "bize", "sizin", "teşekkürler", "lütfen", "bunu"),
        "id" to setOf("yang", "dan", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "dalam", "akan", "pada", "juga", "anda", "kami", "atau", "ke", "adalah", "saya", "kita", "oleh", "karena", "ada", "bisa", "terima", "kasih", "sudah", "mereka", "lebih", "sebagai"),
    )
}
