package app.sterna.bench

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A whole-tree lint: the machine that serves the maintainer's real mail must not be named
 * anywhere in this public checkout.
 *
 * WHY. The bench runs against a private server. Its name kept leaking into the repo through the
 * two doors that look harmless — a KDoc example the maintainer copies at the bench, and an import
 * fixture pasted from a real export — and nothing stopped it coming back. This test is the stop.
 * The public convention is `example.com` / `mail.example.com` (already used by the neighbouring
 * `outlook-xoauth2.k9s` fixture).
 *
 * HOW IT IS BUILT, and each point cost something:
 *
 * - NO `git ls-files`. In the toolchain container a worktree's `.git` points at an absolute
 *    host path that does not exist there; git answers "not a git repository" and zero files, so a
 *    lint built on it would be green and empty in the very tree being written.
 *  - So the tree is WALKED, from the repo root, with exclusions named one by one below. Never a
 *    list of files to scan: a brand-new file must be seen without anyone editing this test.
 * - The forbidden label is NOT written as one literal anywhere in this file. The walk reads
 *    this file too, and a lint that spells its own target reports itself on every run.
 *  - The decision is a PURE FUNCTION, [namesThePrivateHost], EXECUTED against pinned lines by
 *    [theDecisionIsExecutedOnPinnedLines]. A test that re-derived the rule to choose what to feed
 *    it would be a copy of the rule, green under any mutation of it — so the pinned lines are
 *    spelled from their own fragments, deliberately not from the constant the decision uses.
 *  - The walk PROVES IT WALKED ([theWalkReallyCoveredTheTree]). A lint whose scope quietly shrinks
 *    is green and measures nothing, so the file count has a floor AND named witnesses must have
 *    been read. The witnesses matter more than the floor: amputating one published directory
 *    (`fastlane/`, `site/`) leaves hundreds of files behind and clears any floor.
 *  - The walk-and-report is PARAMETERISED BY ITS ROOT ([offencesUnder]) and executed against a
 *    throwaway tree that really carries the label ([aPlantedFileIsReported_andTheTwoExclusionsAreNot]).
 *    That is the only positive case: this checkout is clean, so without it, emptying the report
 *    body leaves the whole suite green.
 */
class NoPrivateHostInTreeTest {

    /**
     * The decision, executed with arguments pinned HERE. The label in these lines is spelled from
     * its own fragments and NOT from [PRIVATE_LABEL]: alter the constant the regex is built from
     * and these lines still carry the real domain, so this test goes red. Deriving them from the
     * same constant would make the test a copy of the rule, green under any mutation of it.
     */
    @Test
    fun theDecisionIsExecutedOnPinnedLines() {
        val label = "pi" + "nty"
        val cases = listOf(
            // The two shapes actually found in the repo, host and address.
            "        <host>mail.$label.fr</host>" to true,
            """ *     "username": "alex@$label.fr",""" to true,
            // Case is not a hiding place.
            ("Server: MAIL." + "PI" + "NTY" + ".FR") to true,
            // The bare label is the target, not one TLD of it.
            "smtpHost = \"mail.$label.example\"" to true,
            // The replacement convention must be clean, and so must an empty line.
            "        <host>mail.example.com</host>" to false,
            "" to false,
            // Word boundaries: a longer word that merely starts with the label is not a hit.
            ("pi" + "ntypalace.example.com") to false,
        )

        val wrong = cases.filter { (line, expected) -> namesThePrivateHost(line) != expected }
        if (wrong.isNotEmpty()) {
            fail(
                "the private-host decision no longer judges these lines as pinned:\n" +
                    wrong.joinToString("\n") { (line, expected) ->
                        "  expected $expected, got ${namesThePrivateHost(line)}  for: $line"
                    } +
                    "\n\nThis is the falsifiable half of the lint: the pattern is assembled from " +
                    "fragments (so this file does not carry the string it forbids), and these " +
                    "lines are spelled from their own fragments so that altering the pattern is " +
                    "visible from here.",
            )
        }
    }

    /**
     * A lint whose scope collapses is green and proves nothing. Fail loudly if the walk did not
     * see a plausible number of files, or missed files known to be there.
     */
    @Test
    fun theWalkReallyCoveredTheTree() {
        val seen = scanned.map { it.toRelativeString(REPO_ROOT).replace(File.separatorChar, '/') }
        val missing = WITNESSES.filterNot { it in seen }
        val fixtures = seen.count { it.endsWith(".k9s") }

        if (seen.size < MIN_FILES || missing.isNotEmpty() || fixtures < MIN_K9S_FIXTURES) {
            fail(
                "the private-host walk did not cover the checkout, so its green means nothing.\n" +
                    "  root      : ${REPO_ROOT.absolutePath}\n" +
                    "  files read: ${seen.size} (floor $MIN_FILES)\n" +
                    "  .k9s read : $fixtures (floor $MIN_K9S_FIXTURES)\n" +
                    "  missing   : ${missing.ifEmpty { listOf("(none)") }.joinToString(", ")}\n" +
                    "\nEither the walk was narrowed (an exclusion that swallowed too much, a root " +
                    "that is no longer the checkout root), or those files moved — in which case " +
                    "fix WITNESSES and say so.",
            )
        }
    }

    /**
     * THE POSITIVE CASE, and the reason [offencesUnder] takes its root as an argument.
     *
     * Without this test nothing ever makes the lint red on a real file: the checkout is clean, so
     * `val text = ""`, or an empty method body, leaves every other test here green. Proving it by
     * planting a file in the checkout by hand proves it once, for the person who did it.
     *
     * So the walk is EXECUTED against a throwaway root that really carries the label, and three
     * things are pinned in one shot — a hit is reported with its path, its LINE NUMBER and its
     * text; a hit inside a nested checkout is not; a hit under a name-excluded directory is not.
     * The planted text is spelled from its own fragments, not from [PRIVATE_LABEL], so altering
     * the pattern makes this go red too.
     */
    @Test
    fun aPlantedFileIsReported_andTheTwoExclusionsAreNot() {
        val leak = "imapHost: mail." + "pi" + "nty" + ".fr"
        val root = Files.createTempDirectory("sterna-private-host-lint").toFile()
        try {
            // Seen: a brand-new file, in a brand-new subdirectory, with the hit on line 2.
            root.resolve("probe/new").mkdirs()
            root.resolve("probe/new/leak.md").writeText("clean first line\n$leak\n")
            // Not seen: a directory carrying a `.git` entry is a checkout of its own.
            root.resolve("nested-checkout").mkdirs()
            root.resolve("nested-checkout/.git").writeText("gitdir: /elsewhere\n")
            root.resolve("nested-checkout/leak.md").writeText("$leak\n")
            // Not seen: a directory excluded by name (untracked working notes).
            root.resolve("ai-work").mkdirs()
            root.resolve("ai-work/leak.md").writeText("$leak\n")

            val expected = listOf("  probe/new/leak.md:2  $leak")
            val actual = offencesUnder(root)
            if (actual != expected) {
                fail(
                    "the walk-and-report did not judge a planted tree as pinned.\n" +
                        "  expected:\n" + expected.joinToString("\n").ifEmpty { "    (none)" } +
                        "\n  actual:\n" + actual.joinToString("\n").ifEmpty { "    (none)" } +
                        "\n\nThree claims live here: a carrier file in a new subdirectory is " +
                        "reported with its path, line number and text; a carrier under a nested " +
                        "checkout is NOT; a carrier under a name-excluded directory is NOT. " +
                        "Whichever moved, the lint no longer measures what it claims.",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** The lint itself — the same function, pointed at the real checkout. */
    @Test
    fun noFileNamesThePrivateHost() {
        val offences = offencesUnder(REPO_ROOT)
        if (offences.isNotEmpty()) {
            fail(
                "the maintainer's own mail machine is named in ${offences.size} line(s) of this " +
                    "public checkout:\n" + offences.joinToString("\n") +
                    "\n\nUse the documented convention instead: host `mail.example.com`, addresses " +
                    "`someone@example.com` (see the outlook-xoauth2.k9s fixture). Keep the " +
                    "example VALID — " +
                    "it is copied at the bench — change only the domain, never a port, a field or " +
                    "the JSON/XML shape. Work notes that legitimately name the machine belong in " +
                    "ai-work/, which git does not track and this walk does not read.",
            )
        }
    }

    private val scanned: List<File> by lazy { scan(REPO_ROOT) }

    private companion object {

        /**
         * The forbidden label, assembled from fragments. Never write it as one literal in this
         * file: the walk reads this file too.
         */
        val PRIVATE_LABEL: String = "pi" + "nt" + "y"

        /**
         * Bare label, case-insensitive, between word boundaries. Bare rather than the full
         * `label.fr` on purpose: the host, an address, a display name ("<label> Mail" was in the
         * fixture) and any future TLD are all the same leak. The boundaries keep a longer word
         * that merely starts with the label from being a hit.
         */
        val PRIVATE_HOST = Regex("(?i)\\b" + PRIVATE_LABEL + "\\b")

        /** THE DECISION. Pure, and executed by [theDecisionIsExecutedOnPinnedLines]. */
        fun namesThePrivateHost(text: String): Boolean = PRIVATE_HOST.containsMatchIn(text)

        /**
         * THE WALK AND ITS REPORT, as one function of its ROOT — so it can be executed against a
         * throwaway tree that carries the label, which is the lint's only positive case. Paths are
         * reported relative to the root it was given, never to the checkout, so the two callers
         * read the same way.
         */
        fun offencesUnder(root: File): List<String> {
            val offences = mutableListOf<String>()
            for (file in scan(root)) {
                val text = file.readText()
                if (!namesThePrivateHost(text)) continue
                val where = file.toRelativeString(root).replace(File.separatorChar, '/')
                text.lines().forEachIndexed { index, raw ->
                    if (namesThePrivateHost(raw)) offences += "  $where:${index + 1}  ${raw.trim()}"
                }
            }
            return offences
        }

        /**
         * Directories (and, for `.git` in a worktree, a file) never read. Named and reasoned one
         * by one — this is an exclusion list, never an inclusion list.
         */
        val SKIPPED_NAMES = setOf(
            ".git", // git's own store; in a worktree the `.git` entry is a pointer FILE
            ".gradle", // Gradle's local cache
            ".idea", // IDE state
            "build", // every module's build outputs, wherever they sit
            "ai-work", // untracked working notes: they name the machine, legitimately
            ".claude", // untracked agent notes and nested worktrees, same reason
        )

        /**
         * Extensions read as bytes, not text. Skipped by extension rather than by sniffing: a
         * mis-sniffed text file would be silently unread, which is the failure this lint exists
         * to avoid.
         */
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "svgz", "pdf",
            "jar", "aar", "apk", "aab", "zip", "gz", "tgz", "bz2", "xz", "7z",
            "so", "dex", "class", "bin", "jks", "keystore",
            "ttf", "otf", "woff", "woff2", "eot",
            "mp3", "mp4", "m4a", "webm", "ogg", "wav",
        )

        /** Nothing this repo keeps as text is a megabyte; past that it is a blob. */
        const val MAX_BYTES = 1L shl 20

        /**
         * The walk reads ~735 files today. The floor is a floor, not a count: it must survive a
         * few files being added or deleted, and must NOT survive the walk being narrowed to a
         * module (the largest single top-level directory holds 269).
         */
        const val MIN_FILES = 400
        const val MIN_K9S_FIXTURES = 3

        /**
         * Known files the walk must have READ — not merely have on disk. One per area that an
         * exclusion could amputate without denting the floor: measured, adding `fastlane` to
         * [SKIPPED_NAMES] still leaves 579 files, and `site` or `xml` are just as cheap. Every one
         * of those three is published material (F-Droid's listing in nine languages, the public
         * site, the manifest), which is exactly where a leak would hurt.
         */
        val WITNESSES = listOf(
            "README.md",
            "settings.gradle.kts",
            ".forgejo/workflows/ci.yml", // a tracked file under a dot directory
            "core/data/src/test/resources/k9s/imap-plain.k9s",
            "app/src/testApp/kotlin/app/sterna/bench/BenchProvisionReceiver.kt",
            "fastlane/metadata/android/en-US/full_description.txt", // what F-Droid publishes
            "site/template.html", // the public site
            "app/src/main/AndroidManifest.xml", // and with it, the `.xml` extension
        )

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val REPO_ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "gradlew").isFile }
                ?: error(
                    "cannot locate the checkout root from ${File("").absolutePath} — this test " +
                        "reads the source tree as text and needs a working directory inside it",
                )
        }

        fun scan(root: File): List<File> = root.walkTopDown()
            .onEnter { dir ->
                dir == root || (
                    dir.name !in SKIPPED_NAMES &&
                        // A directory holding a `.git` entry is a checkout of its own — a git
                        // worktree, a `.wt-*` release tree. Its content belongs to another commit,
                        // really does still carry the old domain, and is not ours to judge.
                        !File(dir, ".git").exists()
                    )
            }
            .filter { it.isFile && it.name !in SKIPPED_NAMES }
            .filter { it.extension.lowercase() !in BINARY_EXTENSIONS }
            .filter { it.length() <= MAX_BYTES }
            .sortedBy { it.path }
            .toList()
    }
}
