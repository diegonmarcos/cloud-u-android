package app.sterna.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app UI language. [SYSTEM] follows the device language; the others force a specific translation.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    FRENCH("fr"),
    SPANISH("es"),
    GERMAN("de"),
    ITALIAN("it"),
    PORTUGUESE("pt"),
    DUTCH("nl"),
    RUSSIAN("ru"),
    POLISH("pl"),
}

/** The language currently in effect, read back from AppCompat. */
fun currentAppLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return AppLanguage.SYSTEM
    val language = locales[0]?.language
    return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag == language }
        ?: AppLanguage.SYSTEM
}

/** Apply [language] app-wide. Triggers an activity recreate so the new strings load. */
fun applyAppLanguage(language: AppLanguage) {
    val locales = if (language == AppLanguage.SYSTEM) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language.tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
