package app.sterna.ui

import app.sterna.ui.settings.OPENKEYCHAIN_FDROID_URL
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST. It reads the shipped strings as text and holds the four
 */
class PgpProviderNamingTest {

    /**
     * The four labels that must name no app at all, in every language.
     */
    private val agnosticKeys = listOf(
        "settings_pgp_missing_help",
        "settings_pgp_provider_required",
        "message_pgp_no_provider",
        "settings_pgp_enable_subtitle",
    )

    @Test
    fun `no language demands one particular OpenPGP app`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        assertEquals(
            "the app ships nine languages; a directory that vanishes takes this rule with it and " +
                "nothing else notices",
            NINE,
            files.map { it.parentFile.name },
        )
        val naming = files.associate { file ->
            val theirs = valuesOf(file)
            file.parentFile.name to agnosticKeys.mapNotNull { key ->
                val text = theirs[key] ?: return@mapNotNull "$key: absent from this language"
                val named = FORBIDDEN.filter { text.contains(it) }
                if (named.isEmpty()) null else "$key names $named: <$text>"
            }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "in this language the OpenPGP section still demands one particular app. A reader who " +
                "signs with PGPony (or any other provider Sterna now binds) is told to install " +
                "OpenKeychain, and the app doing the work is not the one named. These four labels " +
                "name no app; only settings_pgp_install may, because its button really opens " +
                "OpenKeychain's F-Droid page.",
            emptyMap<String, List<String>>(),
            naming,
        )
    }

    /**
     * The other half of the same sentence: with a single provider installed there is no picker row
     */
    @Test
    fun `the subtitle names the app in use, in every language`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val mute = files.associate { file ->
            val text = valuesOf(file)[SUBTITLE]
            file.parentFile.name to when {
                text == null -> "absent from this language"
                !text.contains(ARGUMENT) -> "names no app: <$text>"
                else -> null
            }
        }.filterValues { it != null }
        assertEquals(
            "in this language the \"Use OpenPGP\" subtitle has lost its ${ARGUMENT} argument, so it " +
                "no longer names the OpenPGP app in use. With one provider installed there is no " +
                "picker row, and this line is the only thing on the screen that says which app " +
                "signs and decrypts.",
            emptyMap<String, String?>(),
            mute,
        )
    }

    /**
     * The other half of the periphery, and the half a "make it agnostic" pass is likeliest to
     */
    @Test
    fun `the install button names the one app it really installs, in every language`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val silent = files.mapNotNull { file ->
            val text = valuesOf(file)[INSTALL_BUTTON]
            when {
                text == null -> "${file.parentFile.name}: absent"
                !text.contains("OpenKeychain") -> "${file.parentFile.name}: <$text>"
                else -> null
            }
        }
        assertEquals(
            "the OpenPGP install button stopped naming OpenKeychain in this language, but it " +
                "still opens OpenKeychain's F-Droid page: it now offers a choice of OpenPGP app " +
                "and delivers one particular app. Either both sides move, or neither does.",
            emptyList<String>(),
            silent,
        )
    }

    /**
     * The other direction of the same pair, in its own test on purpose: as a second assertion in
     */
    @Test
    fun `the install button opens the page of the app it names`() {
        assertEquals(
            "the install button's link left OpenKeychain's F-Droid page while its label still " +
                "says OpenKeychain in nine languages: the button names one app and installs another.",
            "https://f-droid.org/packages/org.sufficientlysecure.keychain/",
            OPENKEYCHAIN_FDROID_URL,
        )
    }

    /** Each `<string>`'s text exactly as the file carries it, escapes included. */
    private fun valuesOf(file: File): Map<String, String> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun translations(): List<File> = (res.listFiles() ?: emptyArray<File>())
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        /**
         * The app names these four labels may not carry. OpenKeychain is the one they used to
         */
        val FORBIDDEN = listOf("OpenKeychain", "PGPony")
        const val SUBTITLE = "settings_pgp_enable_subtitle"
        const val INSTALL_BUTTON = "settings_pgp_install"
        const val ARGUMENT = "%1\$s"

        val NINE = listOf(
            "values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru",
        )

        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test reads " +
                        "the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
