package app.sterna.bench

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A source lint over the bench provisioning entry points — every directory of [BENCH_DIRS], i.e.
 */
class BenchProvisionSourceTest {

    @Test
    fun failsLoudlyWhenThereIsNothingToRead() {
        // EACH directory must yield something, never the two of them together: benchShared holds
        // one small file today, so a total count would stay comfortably above zero while the
        // receiver's own directory vanished.
        val silent = BENCH_DIRS.filter { ktFilesUnder(it).isEmpty() }
        if (silent.isNotEmpty()) {
            fail(
                "nothing to read under: " + silent.joinToString(", ") +
                    " — this lint is the ONLY check the bench provisioning code has (the test " +
                    "suite never compiles it, it is behind -PtestApp), so an empty or missing " +
                    "source set means the check is measuring nothing there. Either that code was " +
                    "deleted (then delete the directory from BENCH_DIRS and say so), or it moved " +
                    "(then fix BENCH_DIRS). Looked under ${REPO_ROOT.absolutePath}.",
            )
        }
    }

    @Test
    fun noAccountCreationOutsideTheViewModel() {
        val offences = mutableListOf<String>()
        for (file in benchSources()) {
            file.readText().lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                val rule = FORBIDDEN.firstOrNull { it.second.containsMatchIn(raw) } ?: return@forEachIndexed
                // Whole-line equality, never `contains`: lengthening the line must not launder it.
                if (ALLOWED_LINES.none { it == line }) {
                    offences += "${file.name}:${index + 1}  [${rule.first}]  $line"
                }
            }
        }
        if (offences.isNotEmpty()) {
            fail(
                "the bench provisioning sources must POSE state only through ConnectViewModel's own " +
                    "entry points. These lines create, destroy or re-key accounts by hand, take a " +
                    "secret off a broadcast extra, or open/alter a file outside the pinned two:\n" +
                    offences.joinToString("\n") +
                    "\n\nA fourth add path is what #121 closed: priming the cache before the account " +
                    "exists writes a page of inbox rows under accountId = \"\", rows no account owns " +
                    "and no label can name. A secret in an extra leaks through the host's ps, the " +
                    "driver's failure output and am's own \"Broadcasting: Intent { … }\" echo.\n\n" +
                    "[touches a file] is the newest of the three, and the most literal: this " +
                    "receiver is exported with NO permission, a whole-file write TRUNCATES, and the " +
                    "KDoc of the receiver promises in so many words that it REMOVES NOTHING, EVER. " +
                    "One unpinned line opening a file — even one sitting right beside the guarded " +
                    "write, even one that never runs today — is a second door to that promise being " +
                    "broken by any app on the device. There is exactly one report write, it goes " +
                    "through isBenchOutPathAllowed(), and it is pinned in ALLOWED_LINES. If a new " +
                    "file line is genuinely needed, JUDGE ITS PATH FIRST, then pin it there.",
            )
        }
    }

    @Test
    fun receiverCallsTheViewModelEntryPoints() {
        val lines = benchSources().flatMap { it.readText().lines() }.map { it.trim() }.toSet()
        val missing = VM_ENTRY_POINT_CALLS.filterNot { it in lines }
        if (missing.isNotEmpty()) {
            fail(
                "these exact calls are gone from the bench sources:\n" + missing.joinToString("\n") +
                    "\n\nThe bench adds accounts by calling ConnectViewModel and waiting on its " +
                    "state — nothing else. This is whole-line equality on purpose: rewrite the call " +
                    "and you update this constant, which is the moment someone reads the arguments " +
                    "again. Lines actually found:\n" +
                    lines.filter { it.startsWith("vm.connect") }.joinToString("\n").ifEmpty { "(none)" },
            )
        }
    }

    @Test
    fun eachEntryPointSitsUnderItsOwnBranch() {
        val lines = benchSources().flatMap { it.readText().lines() }.map { it.trim() }
        val offences = mutableListOf<String>()
        for ((label, call) in EXPECTED_BRANCHES) {
            val at = lines.indexOf(call)
            when {
                at < 0 -> offences += "the call is nowhere in the bench sources: $call"
                at == 0 -> offences += "the call is the first line of a file, so it is under no branch: $call"
                // Whole-line equality here too: the label that decides which protocol is dialled
                // is judged exactly like the call it guards.
                lines[at - 1] != label -> offences +=
                    "expected \"$label\" above\n    $call\nbut found \"${lines[at - 1]}\""
            }
        }
        if (offences.isNotEmpty()) {
            fail(
                "a bench entry point is wired to the wrong branch:\n" + offences.joinToString("\n") +
                    "\n\nSwapping two labels leaves every pinned call byte-identical, so the calls " +
                    "alone cannot see it — and an \"imap\" spec dialled through the JMAP entry point " +
                    "connects to an empty server, or worse, succeeds against the wrong one.",
            )
        }
    }

    @Test
    fun theOutPathIsJudgedBeforeTheReportIsWritten() {
        val lines = benchSources().flatMap { it.readText().lines() }.map { it.trim() }.toSet()
        val missing = OUT_PATH_GUARD_LINES.filterNot { it in lines }
        if (missing.isNotEmpty()) {
            fail(
                "the guard on the report's destination is gone from the bench sources. Missing, " +
                    "whole and trimmed:\n" + missing.joinToString("\n") +
                    "\n\nWithout it the receiver writes wherever the `out` extra points, and " +
                    "writeText TRUNCATES: this receiver is exported and carries no permission, so " +
                    "any app on the device could name /data/data/app.sterna.test/files/… and have " +
                    "that file replaced by the report — in a class whose KDoc promises it removes " +
                    "nothing. isBenchOutPathAllowed() lives in src/benchShared/kotlin precisely so " +
                    "a unit test can execute it (BenchOutPathTest); this pins that it is actually " +
                    "CALLED, on the very string that is then handed to File(). Lines found near " +
                    "the write:\n" +
                    lines.filter { "writeText" in it || "BenchOutPathAllowed" in it }
                        .joinToString("\n").ifEmpty { "(none)" },
            )
        }
    }

    private fun benchSources(): List<File> =
        BENCH_DIRS.flatMap { ktFilesUnder(it) }.sortedBy { it.path }

    private fun ktFilesUnder(dir: String): List<File> =
        File(REPO_ROOT, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        /**
         * EVERY directory that ends up in the bench APK. `src/testApp/kotlin` is the receiver;
         */
        val BENCH_DIRS = listOf("app/src/testApp/kotlin", "app/src/benchShared/kotlin")

        /**
         * Repo root, walked up from the module's working directory (as the other source lints do).
         */
        val REPO_ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "source files as text and needs a working directory inside the checkout",
                )
        }

        /**
         * Named rules, found by regex and judged by whole-line equality below. Broad on purpose:
         */
        val FORBIDDEN: List<Pair<String, Regex>> = listOf(
            // Any add-ish call on anything that looks like an account store or the repository.
            // `\w*[Aa]dd\w*` on purpose: addOAuth, readdImportedAccount, addOAuthAccount — a rule
            // that only knew the names already written was the hole this closes.
            "creates an account behind the ViewModel" to
                Regex("""\b(accountStore|store|repo|mailRepository|container)\s*\.\s*\w*[Aa]dd\w*\s*\("""),
            // Same call reached through a differently named handle: matched on the store's own
            // argument names, which no list or map `add` takes.
            "creates an account behind the ViewModel" to Regex("""\.\s*\w*[Aa]dd\w*\s*\(\s*(server|username)\s*="""),
            "builds a second account store" to Regex("""AccountStore\s*\("""),
            "re-implements the ordered add" to Regex("""addAccountThenPrime"""),
            "borrows the settings-import add path" to Regex("""restoreImportAccount|importAccounts\s*\("""),
            "primes the cache by hand" to Regex("""saveInboxMetaFor|primeInbox"""),
            "removes what the bench must never remove" to
                Regex("""\b(accountStore|store)\s*\.\s*(remove|removeCascading|clear)\s*\(|\bsignOut\s*\("""),
            "writes the server-owned identity list (erased on the next connect)" to
                Regex("""setServerIdentities\s*\("""),
            "rewrites a stored secret" to Regex("""updatePassword\s*\(|updateOAuthTokens\s*\(|writePassword\s*\("""),
            "moves the current account under the user" to Regex("""setCurrent\s*\("""),
            // INVERTED RULE, and the reason ALLOWED_LINES exists: EVERY read of an intent extra
            // is forbidden, and the two legitimate ones are pinned below, whole. Listing secret
            // WORDS instead ("password|token|…") only ever caught the names someone had thought
            // of — `getStringExtra("pass")` walked straight through.
            "reads a broadcast extra" to Regex("""\bget\w*Extra\s*\("""),
            "names an extra after a secret" to Regex("""(?i)EXTRA_\w*(PASSWORD|PASSWD|PWD|SECRET|TOKEN|PASS|CRED|KEY)"""),
            // SECOND INVERTED RULE, and it is inverted for the same reason as the extras above:
            // EVERY line that opens or alters a file is forbidden, and the legitimate ones are
            "touches a file" to Regex(
                """\bFile\s*\(|\bFile(OutputStream|Writer)\s*\(|\bFiles\s*\.|""" +
                    """\b(writeText|writeBytes|appendText|appendBytes|outputStream|bufferedWriter""" +
                    """|printWriter|createNewFile|createTempFile|mkdir|mkdirs|delete|deleteRecursively""" +
                    """|deleteOnExit|renameTo|copyTo|copyRecursively|openFileOutput|setWritable)\s*\(""",
            ),
        )

        /**
         * Lines exempted from [FORBIDDEN], compared whole and trimmed. Each entry is a promise that
         */
        val ALLOWED_LINES: List<String> = listOf(
            "val specPath = intent.getStringExtra(EXTRA_SPEC)",
            "val outPath = intent.getStringExtra(EXTRA_OUT)",
            // The two file lines the bench is allowed to have, and no others: the spec handle it
            // reads, and the ONE report write, the one that sits behind isBenchOutPathAllowed().
            // Any third line that opens a file — even an innocent-looking one right beside this
            // write — is a second, unjudged door to the same truncation.
            "val file = File(specPath)",
            "else -> runCatching { File(out).writeText(text) }.onFailure {",
        )

        /**
         * The three production entry points the bench may add an account with, each with the `when`
         */
        val EXPECTED_BRANCHES: List<Pair<String, String>> = listOf(
            "Mode.JMAP ->" to
                "vm.connect(server = spec.server, username = spec.username, password = spec.password, accountName = spec.accountName)",
            "Mode.JMAP_AUTO ->" to
                "vm.connectAuto(email = spec.username, password = spec.password, accountName = spec.accountName)",
            "Mode.IMAP ->" to
                "vm.connectImap(username = spec.username, password = spec.password, accountName = spec.accountName, imapHost = spec.imapHost, imapPort = spec.imapPort, imapSecurity = spec.imapSecurity, smtpHost = spec.smtpHost, smtpPort = spec.smtpPort, smtpSecurity = spec.smtpSecurity)",
        )

        val VM_ENTRY_POINT_CALLS: List<String> = EXPECTED_BRANCHES.map { it.second }

        /**
         * The two lines that keep the report inside `/data/local/tmp`: the judgement, and the
         */
        val OUT_PATH_GUARD_LINES: List<String> = listOf(
            "!isBenchOutPathAllowed(out) -> Log.e(",
            "else -> runCatching { File(out).writeText(text) }.onFailure {",
        )
    }
}
