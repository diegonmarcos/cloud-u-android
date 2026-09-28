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

    // -- 3. completeness, against the SCHEMA and not against a neighbour ----------------------

    /**
     * This used to read `keys(keyboardEntry) - keys(mailEntry)`, calling the keyboard
     * "the reference shape". That encoded ONE app's field set as the fleet's schema,
     * and #631 made the field set deliberately per-app: `version_name`/`version_code`
     * are emitted only where `build.json::android` declares them, because "an absent
     * key is the honest answer" and a fork must not borrow upstream's number. The
     * keyboard declares them and mail does not, so from #631 onward this test failed
     * on an honest difference — a false red, and a landmine for every future app that
     * legitimately declares a different set.
     *
     * The floor that actually means something is the INTERSECTION: a key carried by
     * EVERY app entry is structural, and mail missing one of those is a real defect.
     * A key carried by only some entries is optional by construction and cannot be
     * asserted from a neighbour.
     */
    @Test fun `mail's entry carries every field that EVERY app entry carries`() {
        val missing = universalAppKeys() - keys(mailBlock)
        assertEquals(
            "these keys are present on every entry in the fleet, so they are the schema " +
                "rather than one app's shape. One of them missing here is a fact Configs ▸ " +
                "Update cannot show about mail that it can show about every other app",
            emptySet<String>(),
            missing,
        )
    }

    @Test fun `the intersection floor is not vacuous`() {
        // A floor computed from every entry would be EMPTY if the entry splitter ever
        // stopped finding entries, and an empty floor subtracts to nothing and passes
        // forever. Proving the floor is real is what keeps the test above from becoming
        // decoration the day the manifest's layout changes.
        val universal = universalAppKeys()
        assertTrue(
            "the fleet must yield a non-trivial common key set; got $universal",
            universal.containsAll(setOf("id", "package", "asset", "release_url", "kind")),
        )
        assertEquals(
            "a synthetic entry missing a structural key must be REPORTED by the same " +
                "subtraction the test above performs — otherwise that test cannot fail",
            setOf("release_url"),
            universal - (keys(mailBlock) - setOf("release_url")),
        )
    }

    /**
     * The other half, and the one that makes a DECLARED field non-optional: whatever
     * this app's own build.json declares must reach its entry. #631's rule is
     * "emitted from the same declaration gradle reads, and not emitted at all where
     * nothing declares it" — so the honest assertion is conditional on the
     * declaration, not on a neighbour. Today mail declares no `android` block at all
     * and this holds trivially; the moment it declares one, a regen that drops it
     * goes red here.
     */
    @Test fun `no field mail's own build_json declares is missing from its entry`() {
        val missing = declaredOptionalFields(MAIL_BUILD_JSON.readText()) - keys(mailBlock)
        assertEquals(
            "build.json declares it and the manifest dropped it: Configs ▸ Update would " +
                "show 'not declared' for a version this app does declare",
            emptySet<String>(),
            missing,
        )
    }

    @Test fun `the declared-field check goes RED when a declared field is dropped`() {
        // THE MUTATION. Without it the test above is indistinguishable from one that
        // asserts nothing, because mail currently declares nothing for it to find —
        // exactly how a conditional assertion becomes a blind one.
        val declaresBoth = """{"android":{"version_name":"9.9.9","version_code":424242}}"""
        assertEquals(
            "a build.json declaring both version fields, against an entry carrying " +
                "neither, must report both as missing",
            setOf("version_name", "version_code"),
            declaredOptionalFields(declaresBoth) - keys(mailBlock),
        )
        assertEquals(
            "…and a build.json declaring nothing must report nothing, so the check is " +
                "conditional on the declaration and not simply always-empty",
            emptySet<String>(),
            declaredOptionalFields("""{"upstream":{"version":"1.5.4"}}""") - keys(mailBlock),
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
        val mailBlock: String by lazy { objectContaining("package", "com.diegonmarcos.comms.mail") }

        /**
         * Every entry in `apps`, as text. Brace-matched from the `"apps":[` array so
         * the splitter does not depend on the one-entry-per-line layout regen.sh
         * happens to write today.
         */
        fun appBlocks(): List<String> {
            // `"apps"` FOLLOWED BY A COLON, not the bare token: the groups array
            // contains {"id":"apps","label":"Apps",…}, so a plain indexOf("\"apps\"")
            // finds that VALUE first and then brace-matches the wrong array entirely —
            // yielding zero entries, an empty intersection floor, and a completeness
            // check that passes vacuously. Found by porting this to python and
            // counting entries against jq.
            val at = Regex("\"apps\"\\s*:\\s*\\[").find(fleetText)
                ?: error("$FLEET_PATH has no `apps` array")
            val open = fleetText.indexOf('[', at.range.first)
            val blocks = mutableListOf<String>()
            var i = open + 1
            var depth = 0
            var start = -1
            var inString = false
            var escaped = false
            while (i < fleetText.length) {
                val c = fleetText[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> { if (depth == 0) start = i; depth++ }
                    c == '}' -> { depth--; if (depth == 0 && start >= 0) { blocks += fleetText.substring(start, i + 1); start = -1 } }
                    c == ']' && depth == 0 -> return blocks
                }
                i++
            }
            error("$FLEET_PATH's `apps` array is unterminated")
        }

        /** Keys carried by EVERY app entry — the fleet's structural floor, derived from
         *  the data rather than from one app that happens to sit next to this one. */
        fun universalAppKeys(): Set<String> =
            appBlocks().map { keys(it) }.reduceOrNull { a, b -> a intersect b }
                ?: error("$FLEET_PATH yielded no app entries — the splitter is broken, and an " +
                    "empty floor would make every completeness check pass vacuously")

        /**
         * The text of the `{ … }` that starts at [open], brace-matched and skipping
         * string literals. Shared by [objectContaining] and [declaredOptionalFields]
         * so there is one brace matcher rather than two that can disagree.
         */
        fun braceMatched(text: String, open: Int): String {
            var depth = 0
            var i = open
            var inString = false
            var escaped = false
            while (i < text.length) {
                val c = text[i]
                if (escaped) {
                    escaped = false
                } else if (c == '\\' && inString) {
                    escaped = true
                } else if (c == '"') {
                    inString = !inString
                } else if (!inString) {
                    if (c == '{') depth++
                    if (c == '}') {
                        depth--
                        if (depth == 0) return text.substring(open, i + 1)
                    }
                }
                i++
            }
            error("unbalanced braces from offset $open")
        }

        /** The fields regen.sh emits ONLY where build.json declares them (#631):
         *  `android.version_name` / `android.version_code`. Empty when there is no
         *  `android` block at all, which is mail's state today. */
        fun declaredOptionalFields(buildJsonText: String): Set<String> {
            val at = Regex("\"android\"\\s*:\\s*\\{").find(buildJsonText) ?: return emptySet()
            val android = braceMatched(buildJsonText, buildJsonText.indexOf('{', at.range.first))
            val found = mutableSetOf<String>()
            if (Regex("\"version_name\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(android)) {
                found += "version_name"
            }
            // > 0, the same threshold regen.sh applies: a declared 0 is not a version.
            val code = Regex("\"version_code\"\\s*:\\s*(\\d+)").find(android)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (code != null && code > 0) found += "version_code"
            return found
        }

        val mail: Map<String, String> by lazy {
            keys(mailBlock).associateWith { k -> stringValue(mailBlock, k) ?: "" }
        }

        /**
         * The smallest `{ … }` in the manifest whose [key] holds [value].
         *
         * Brace-matched from the opening brace and skipping over string literals,
         * rather than split on a separator — a separator-based slice silently
         * returns the wrong entry the day the file is reformatted, and returns it
         * with a passing status. Fails closed: no needle, or no matching brace, is
         * an error and not an empty string.
         *
         * The needle tolerates any whitespace around the colon. regen.sh writes one
         * compact entry per line since #343 (`"package":"…"`), and a needle spelled
         * with the old pretty-printed `": "` found nothing, which failed every
         * test in this file for a layout change rather than a wrong address.
         */
        fun objectContaining(key: String, value: String): String {
            val needle = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"${Regex.escape(value)}\"")
            val at = needle.find(fleetText)?.range?.first
                ?: error("$FLEET_PATH contains no \"$key\": \"$value\" — the entry this test is about is gone")
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

        /**
         * Top-level key names of a JSON object given as text, whatever its layout.
         *
         * Depth-counted rather than anchored to indentation: the old
         * `^\s{0,6}"key":` pattern only ever matched the pretty-printed manifest,
         * and on the compact one-entry-per-line form it returned no keys at all.
         */
        fun keys(block: String): Set<String> {
            val found = mutableSetOf<String>()
            var depth = 0
            var i = 0
            while (i < block.length) {
                when (block[i]) {
                    '"' -> {
                        val start = i + 1
                        var end = start
                        while (end < block.length && block[end] != '"') {
                            if (block[end] == '\\') end++
                            end++
                        }
                        var next = end + 1
                        while (next < block.length && block[next].isWhitespace()) next++
                        if (depth == 1 && next < block.length && block[next] == ':') {
                            found += block.substring(start, end)
                        }
                        i = end
                    }
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                i++
            }
            return found
        }

        /** A string-valued field of a JSON object given as text, or null. */
        fun stringValue(block: String, key: String): String? =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1)

        /** A string value from this app's build.json, by key name. */
        fun buildJsonValue(key: String): String? = stringValue(MAIL_BUILD_JSON.readText(), key)
    }
}
