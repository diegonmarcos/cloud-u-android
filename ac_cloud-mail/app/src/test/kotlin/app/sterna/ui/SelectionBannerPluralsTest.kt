package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SelectionBannerPluralsTest {

    @Test
    fun `every plural form of the selection banner carries its number`() {
        val offenders = mutableListOf<String>()
        for (file in localeFiles()) {
            val locale = file.parentFile.name
            for (key in KEYS) {
                val body = pluralBody(file, key)
                if (body == null) {
                    offenders += "$locale/$key: no <plurals> at all"
                    continue
                }
                val items = ITEM.findAll(body).toList()
                if (items.isEmpty()) offenders += "$locale/$key: <plurals> with no <item>"
                val categories = items.map { it.groupValues[1] }.sorted()
                val expected = CATEGORIES[locale]
                if (expected != null && categories != expected.sorted()) {
                    offenders += "$locale/$key: categories $categories, expected $expected — " +
                        "a missing one falls back to 'other' and reads wrong for the numbers it covered"
                }
                for (item in items) {
                    val quantity = item.groupValues[1]
                    val text = item.groupValues[2]
                    if (NUMBER !in text) {
                        offenders += "$locale/$key/$quantity: \"$text\" has no %1\$d — " +
                            "in ru/pl the 'one' form also covers 21, 31, 101"
                    }
                }
            }
        }
        assertEquals("plural forms with no number in them", emptyList<String>(), offenders)
    }

    /** `values/` plus every `values-xx/` that ships strings — nine files as of this change. */
    private fun localeFiles(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    /** The inner text of `<plurals name="[key]">`, or null when the language has no such block. */
    private fun pluralBody(file: File, key: String): String? =
        Regex("<plurals\\s+name=\"$key\"[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)

    private companion object {
        val KEYS = listOf(
            "status_selection_deleted",
            "status_selection_archived",
            "status_selection_moved",
            "status_selection_copied_not_removed",
        )

        val ITEM = Regex("<item\\s+quantity=\"([^\"]+)\"\\s*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)

        /**
         * The CLDR categories each language must carry, following the same reading of them as the
         */
        val CATEGORIES = mapOf(
            "values" to listOf("one", "other"),
            "values-de" to listOf("one", "other"),
            "values-es" to listOf("one", "other"),
            "values-it" to listOf("one", "other"),
            "values-nl" to listOf("one", "other"),
            "values-pt" to listOf("one", "other"),
            "values-fr" to listOf("one", "many", "other"),
            "values-pl" to listOf("one", "few", "many", "other"),
            "values-ru" to listOf("one", "few", "many", "other"),
        )

        /** The one placeholder the banner is formatted with, written out so a `%d` cannot pass. */
        const val NUMBER = "%1\$d"

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error("cannot locate app/src/main/res from ${File("").absolutePath}")
        }
    }
}
