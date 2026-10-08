package app.sterna.ui.text

import java.util.Locale

/**
 * The languages Translate and the summary can be pointed at: the dropdown's rows.
 *
 * A fixed list of BCP-47 tags, named by the platform in the device's own language, because the list
 * of what an engine can reach is the engine's and not always bound when a Settings page is drawn.
 * The tags are the ones the fleet's translate engine accepts; a tag the engine turns out not to know
 * fails at run time with the engine's own reason, which the reader shows.
 */
object MailLanguages {
    const val DEFAULT = "en"

    val TAGS: List<String> = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et",
        "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "ka",
        "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru", "sk",
        "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    /** What a stored value means: blank is the default (English); case and `_` are normalised. */
    fun normalise(tag: String?): String =
        tag.orEmpty().trim().ifEmpty { DEFAULT }.lowercase().replace('_', '-')

    /** [tag]'s name for a reader whose language is [inLocale]; the tag itself if the platform has none. */
    fun nameOf(tag: String, inLocale: Locale = Locale.getDefault()): String {
        val locale = Locale.forLanguageTag(normalise(tag))
        return locale.getDisplayName(inLocale).takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(inLocale) }
            ?: tag
    }

    /** The dropdown's rows: every tag with its name, sorted by name; [selected] is added if unknown. */
    fun options(selected: String, inLocale: Locale = Locale.getDefault()): List<Pair<String, String>> {
        val current = normalise(selected)
        val tags = if (current in TAGS) TAGS else TAGS + current
        return tags.map { it to nameOf(it, inLocale) }.sortedBy { it.second.lowercase(inLocale) }
    }
}
