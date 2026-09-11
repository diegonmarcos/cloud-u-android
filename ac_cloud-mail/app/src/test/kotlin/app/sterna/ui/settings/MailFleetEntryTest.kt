package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Configs ▸ Update shows four addresses. This file is where they are TRUE.
 *
 * ## Why against the file and not against a constant
 * "The links are non-empty strings" is a test that passes for four wrong links.
 * What matters is that what the page will show equals what
 * `constellation-fleet.json` says — the fleet's single source of truth for where
 * this app is published — so this reads that file and compares against it.
 *
 * ## And why the build wiring is pinned too
 * :libs:updater bakes a fleet manifest from `${rootDir}/data/constellation-fleet.json`,
 * the consuming app's own data/ dir. ac_cloud-mail HAS NO data/ DIR, so the
 * library's `CONSTELLATION_FLEET_B64` is the empty string in every cloud-mail
 * build. A page reading it finds no entry for itself and draws no links at all —
 * and a page with no links looks, from a screenshot and from the source, exactly
 * like a page whose links are fine. That regression has one shape and this file
 * has an assertion for it.
 *
 * ## What this file CANNOT see
 * It reads JSON and source as text. It proves the values are right and that the
 * build is wired to the right manifest; it does not prove a row is drawn, that a
 * tap opens anything, or that the address resolves. Only a device proves those.
 */
class MailFleetEntryTest {

    // -- 1. the four addresses, against the manifest ------------------------------------------

    @Test fun `the release download URL is the one the fleet publishes`() {
        assertEquals(
            "Configs ▸ Update's APK row opens this, and :libs:updater downloads it. Two " +
                "different strings would mean the page advertises one artefact and the button " +
                "fetches another",
            "https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/cloud-comms-mail.apk",
            mail["release_url"],
        )
    }

    @Test fun `the repository URL points at this app's own tree`() {
        assertEquals(
            "https://github.com/diegonmarcos/cloud-u-android/tree/main/ac_cloud-mail",
            mail["repo_url"],
        )
    }

    @Test fun `the package page is this app's own GHCR container`() {
        assertEquals(
            "https://github.com/diegonmarcos/cloud-u-android/pkgs/container/cloud-comms-mail",
            mail["ghcr_page"],
        )
    }

    @Test fun `the asset is the name the ship engine actually uploads`() {
        assertEquals("cloud-comms-mail.apk", mail["asset"])
        assertEquals(
            "release.gh_release.asset_name in this app's build.json is what CI uploads with " +
                "--clobber. Disagree with it and the page names a file that is never published",
            mail["asset"],
            buildJsonValue("asset_name"),
        )
    }

    @Test fun `the entry belongs to this app and no other`() {
        assertEquals("com.diegonmarcos.comms.mail", mail["package"])
        assertEquals(
            "build.json::forks.mail.app_id is the single source of truth for the package id, " +
                "and app/build.gradle.kts selects the fleet entry BY that id. If these two ever " +
                "disagree the build fails rather than baking a stranger's addresses",
            mail["package"],
            buildJsonValue("app_id"),
        )
        assertEquals("cloud-comms-mail", mail["image"])
    }

    // -- 2. the derived page, joined to the file ----------------------------------------------

    @Test fun `the release page derives from the manifest's own download URL`() {
        val releaseUrl = mail.getValue("release_url")
        assertEquals(
            "https://github.com/diegonmarcos/cloud-u-android/releases/tag/latest",
            UpdateLinks.releasePageUrl(releaseUrl),
        )
        assertEquals(
            "the filename the page shows must be the filename in the URL it links, and the " +
                "asset the manifest names. Three places, one string",
            mail["asset"],
            UpdateLinks.assetName(releaseUrl),
        )
    }

    // -- 3. completeness against a neighbour --------------------------------------------------

    @Test fun `mail's entry carries every field the keyboard's does`() {
        val missing = keys(keyboardBlock) - keys(mailBlock)
        assertEquals(
            "the keyboard entry is the reference shape. A field present there and absent here " +
                "is a fact Configs ▸ Update cannot show about mail but could about the keyboard",
            emptySet<String>(),
            missing,
        )
    }

    @Test fun `no address in the entry is blank`() {
        val blank = listOf("release_url", "repo_url", "ghcr_page", "asset", "package", "image")
            .filter { mail[it].isNullOrBlank() }
        assertEquals("a blank address renders as no row at all", emptyList<String>(), blank)
    }

    @Test fun `every address is HTTPS`() {
        // TLS is the entire verification that stands between a tapped link and a
        // substituted page, and between the downloader and a substituted binary.
        val notTls = listOf("release_url", "repo_url", "ghcr_page")
            .filterNot { mail[it].orEmpty().startsWith("https://") }
        assertEquals(emptyList<String>(), notTls)
    }

    // -- 4. the empty-manifest regression -----------------------------------------------------

    @Test fun `the build bakes this app's own entry out of the fleet manifest`() {
        val gradle = codeLines(BUILD_GRADLE)
        // `rootProject.file(` as well as the filename, deliberately: the two error
        // messages beside that line NAME the manifest in prose, and prose is not a
        // read. Matching the filename alone would count them and has to be wrong in
        // one of the two directions — this is the shape of guard that has been
        // satisfied by its own explanation in this repository before.
        val reads = gradle.filter { "constellation-fleet.json" in it && "rootProject.file(" in it }
        assertEquals(
            "app/build.gradle.kts must read the manifest from the sibling superapp tree, named " +
                "once and in one place. Found: $reads",
            listOf("val manifest = rootProject.file(\"../aa_cloud-superapp/data/constellation-fleet.json\")"),
            reads,
        )
        assertTrue(
            "the entry must be selected BY PACKAGE ID against commsApplicationId, not by the " +
                "name \"mail\" — a renamed entry would otherwise hand this app another app's " +
                "release URL. Found none of that in app/build.gradle.kts",
            gradle.any { "it[\"package\"] == commsApplicationId" in it },
        )
        assertTrue(
            "a missing entry must fail the build. A mail APK that cannot say where it came " +
                "from is the defect this block removes, not something to ship quietly",
            gradle.any { it.startsWith("?: error(") || it.startsWith("?:error(") },
        )
    }

    @Test fun `the screen reads the manifest that has something in it`() {
        val screen = codeLines(UPDATE_SCREEN)
        assertEquals(
            "UpdateScreen must parse BuildConfig.MAIL_FLEET_B64 — this app's own entry, baked " +
                "by app/build.gradle.kts",
            listOf("Fleet.parse(BuildConfig.MAIL_FLEET_B64)"),
            screen.filter { "Fleet.parse(" in it },
        )
        // Comment lines are stripped above, which matters here: the KDoc on that
        // file NAMES CONSTELLATION_FLEET_B64 to explain why it is not used. A
        // match against raw text would find the prose and call it a call site —
        // the exact mistake a guard in this repository has made before.
        assertEquals(
            "CONSTELLATION_FLEET_B64 is :libs:updater's copy, read from a data/ dir this app " +
                "does not have, and is therefore the EMPTY STRING here. Reading it draws a page " +
                "with no links that looks exactly like one that works",
            emptyList<String>(),
            screen.filter { "CONSTELLATION_FLEET_B64" in it },
        )
    }

    @Test fun `the guard above can tell a call site from a comment about one`() {
        // Proving the stripper, not the screen: if codeLines ever stopped removing
        // comments, the assertion above would go red on UpdateScreen's own KDoc and
        // the next person would "fix" it by deleting the explanation.
        val raw = UPDATE_SCREEN.readLines().count { "CONSTELLATION_FLEET_B64" in it }
        assertTrue(
            "UpdateScreen.kt's KDoc must keep explaining why the library's manifest is not " +
                "used; this test exists to prove the guard ignores that prose rather than " +
                "being satisfied by it",
            raw > 0,
        )
        assertEquals(
            "…and codeLines must remove every one of those mentions",
            emptyList<String>(),
            codeLines(UPDATE_SCREEN).filter { "CONSTELLATION_FLEET_B64" in it },
        )
    }

    // -- plumbing -------------------------------------------------------------------------------

    /** Kotlin source with comment lines removed, so prose cannot satisfy a check. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {

        /** Repo root, walked up from the module's working directory. Fails closed. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, FLEET_PATH).isFile }
                ?: error(
                    "cannot locate $FLEET_PATH from ${File("").absolutePath}. This test reads " +
                        "the fleet manifest as text and needs a working directory inside a full " +
                        "checkout — the same sibling tree app/build.gradle.kts bakes it from.",
                )
        }

        const val FLEET_PATH = "aa_cloud-superapp/data/constellation-fleet.json"

        val fleetText: String by lazy { File(root, FLEET_PATH).readText() }

        val BUILD_GRADLE: File by lazy { existing("ac_cloud-mail/app/build.gradle.kts") }
        val UPDATE_SCREEN: File by lazy {
            existing("ac_cloud-mail/app/src/main/kotlin/app/sterna/ui/settings/UpdateScreen.kt")
        }
        val MAIL_BUILD_JSON: File by lazy { existing("ac_cloud-mail/build.json") }

        fun existing(rel: String): File = File(root, rel).also {
            if (!it.isFile) error("$rel is not at ${it.path} — was it moved? This check cannot pass without reading it.")
        }

        /** The `mail` entry's own JSON object, as text. */
        val mailBlock: String by lazy { objectContaining("\"package\": \"com.diegonmarcos.comms.mail\"") }

        /** The `keyboard` entry, the reference shape for completeness. */
        val keyboardBlock: String by lazy { objectContaining("\"package\": \"com.diegonmarcos.cloudkeyboard\"") }

        val mail: Map<String, String> by lazy {
            keys(mailBlock).associateWith { k -> stringValue(mailBlock, k) ?: "" }
        }

        /**
         * The smallest `{ … }` in the manifest that contains [needle].
         *
         * Brace-matched from the opening brace and skipping over string literals,
         * rather than split on a separator — a separator-based slice silently
         * returns the wrong entry the day the file is reformatted, and returns it
         * with a passing status. Fails closed: no needle, or no matching brace, is
         * an error and not an empty string.
         */
        fun objectContaining(needle: String): String {
            val at = fleetText.indexOf(needle)
            if (at < 0) error("$FLEET_PATH contains no $needle — the entry this test is about is gone")
            val open = fleetText.lastIndexOf('{', at)
            if (open < 0) error("no opening brace before $needle in $FLEET_PATH")
            var depth = 0
            var i = open
            var inString = false
            var escaped = false
            while (i < fleetText.length) {
                val c = fleetText[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) return fleetText.substring(open, i + 1)
                    }
                }
                i++
            }
            error("unbalanced braces after $needle in $FLEET_PATH")
        }

        /** Top-level key names of a JSON object given as text. */
        fun keys(block: String): Set<String> =
            Regex("""^\s{0,6}"([^"]+)"\s*:""", RegexOption.MULTILINE)
                .findAll(block).map { it.groupValues[1] }.toSet()

        /** A string-valued field of a JSON object given as text, or null. */
        fun stringValue(block: String, key: String): String? =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1)

        /** A string value from this app's build.json, by key name. */
        fun buildJsonValue(key: String): String? = stringValue(MAIL_BUILD_JSON.readText(), key)
    }
}
