package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ConnectPrimingWiringTest {

    /** The three add paths, by the function each one lives in. */
    private val addPaths = listOf("finishJmapConnect", "finishTokenConnect", "connectImap")

    /**
     * The identity each add path must ask about, spelled out whole. The ARGUMENTS are the rule: an
     */
    private val addGuards = mapOf(
        "finishTokenConnect" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, resolved, "", address)) == null""",
        "finishJmapConnect" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, server, "", username)) == null""",
        "connectImap" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.IMAP, "", imapHost, username)) == null""",
    )

    /**
     * Every path that has to SAY which of the two happened — the three add paths plus the device
     */
    private val sayingPaths = addPaths + "pollForToken"

    /** The two paths that probe for a server before any account exists. */
    private val discoveryPaths = listOf("connectAuto", "connectToken")

    /**
     * Every route the "Connect" button can take, all six of them. Offline — airplane mode, Wi-Fi
     */
    private val offlineGuarded = listOf(
        "connect", "connectAuto", "connectToken", "connectOAuth", "connectOutlookOAuth", "connectImap",
    )

    /**
     * And the two sign-ins the import flow puts on this same screen. They answer through the
     */
    private val offlineGuardedImports = listOf("submitImportPassword", "startImportOAuth")

    /**
     * What "the guard comes first" is measured against: the two spellings by which a route hands
     */
    private val requestHandoffs = listOf("viewModelScope.launch", "container.")

    /**
     * Every call in THIS FILE that brings an account into existence. It is a vocabulary, not a
     */
    private val accountCreation = listOf(
        "container.accountStore.add(",
        "container.accountStore.readdImportedAccount(",
        "container.mailRepository.addOAuthAccount(",
    )

    /**
     * The functions of this file that create an account WITHOUT going through the decision, and why
     */
    private val creatorsOutsideTheDecision = listOf("pollForToken", "restoreImportAccount")

    /** Those, plus the writes a re-add makes on the account it found. */
    private val accountStoreWrites = accountCreation +
        listOf("container.accountStore.updatePassword(", "container.accountStore.setCurrent(")

    @Test fun `the screen refreshes in exactly one place`() {
        val calls = REFRESH.findAll(source()).map { it.value }.toList()
        assertEquals(
            "ConnectViewModel must call mailRepository.refresh() exactly once, from primeInbox(). " +
                "Every other call is an add path priming the cache with credentials it built " +
                "itself — which is how mail got written under an empty account id (#121). Found:\n" +
                calls.joinToString("\n"),
            1, calls.size,
        )
    }

    @Test fun `that one refresh is the one primeInbox makes`() {
        val body = functionBody("primeInbox")
        assertTrue(
            "primeInbox() must be the function that refreshes: it is the single point where the " +
                "credentials priming the cache are the id-stamped ones. Body was:\n$body",
            REFRESH.containsMatchIn(body),
        )
        assertTrue(
            "primeInbox() must record the inbox meta against the id it primed (saveInboxMetaFor), " +
                "not against whichever account happens to be current. Body was:\n$body",
            "saveInboxMetaFor(" in body,
        )
    }

    @Test fun `every add path goes through the create-then-prime decision`() {
        val offenders = addPaths.filterNot { "addAccountThenPrime(" in functionBody(it) }
        assertEquals(
            "each add path must add the account through addAccountThenPrime(), which is what proves " +
                "the credentials first, then creates the account, then primes the cache under its " +
                "id. A path left out keeps writing mail under no account for its protocol (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The rule the previous one does NOT hold: it proves each path reaches the decision, nothing
     */
    @Test fun `every add path primes with the credentials the decision stamped`() {
        val offenders = addPaths.filterNot { PRIME_LAMBDA in functionBody(it) }
        assertEquals(
            "each add path must pass `$PRIME_LAMBDA`: `it` is the copy addAccountThenPrime stamped " +
                "with the new account id. Handing it the path's own `probe` (or any credentials " +
                "built before the account existed) writes the inbox under accountId = \"\" again, " +
                "which is #121 exactly.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * Priming must be the LAST step, so nothing exists in the store before the credentials are
     */
    @Test fun `no add path writes to the account store before the decision runs`() {
        val offenders = addPaths.mapNotNull { path ->
            val body = functionBody(path)
            val decision = body.indexOf("addAccountThenPrime(")
            val early = accountStoreWrites.filter { call ->
                val at = body.indexOf(call)
                at >= 0 && at < decision
            }
            if (early.isEmpty()) null else "$path: ${early.joinToString()}"
        }
        assertEquals(
            "an add path must not touch the account store before addAccountThenPrime() — every " +
                "store write belongs inside its `persist` lambda, which only runs once " +
                "testConnection() has proven the credentials. Anything earlier leaves an account " +
                "behind on a mistyped password (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * WHAT THIS PROMISES, EXACTLY — and it is narrower than "the account-creating functions of
     */
    @Test fun `every account-creating call this lint names sits in a function it has judged`() {
        val creators = source().lines().withIndex()
            .filter { (_, line) -> accountCreation.any { it in line } }
            .map { (i, _) -> enclosingFunction(i) }
            .distinct()
        assertEquals(
            "a function of ConnectViewModel calls one of the creation functions this lint names " +
                "(${accountCreation.joinToString()}) without being listed here. If it is an add " +
                "path, put it in `addPaths` and make it go through addAccountThenPrime; if it " +
                "deliberately creates one another way, list it in `creatorsOutsideTheDecision` " +
                "with the reason. Leaving it out lets the rules above read right past it. ⚠ And " +
                "note what this does NOT say: a creation written any other way is invisible to " +
                "this rule, so a green run here is not \"nothing else creates an account\".",
            (addPaths + creatorsOutsideTheDecision).sorted(), creators.sorted(),
        )
    }

    /**
     * And the vocabulary must be alive. `container.accountStore.addOAuth(` sat in that list while
     */
    @Test fun `every account-creating call this lint knows still exists in the file`() {
        val dead = accountCreation.filterNot { it in source() }
        assertEquals(
            "these calls no longer occur in ConnectViewModel, so the exhaustivity rule above is " +
                "searching for nothing on their behalf. Either the path was renamed (update the " +
                "string) or it is gone (drop it) — do not leave a name that cannot match.",
            emptyList<String>(), dead,
        )
    }

    /**
     * Cancellation is not a sign-in failure: leaving the screen while the inbox loads cancels this
     */
    @Test fun `every add path lets cancellation through instead of showing it`() {
        val offenders = addPaths.filterNot { "catch (cancelled: CancellationException)" in functionBody(it) }
        assertEquals(
            "each add path must rethrow CancellationException before its catch-all: leaving the " +
                "screen mid-add is not an error to report, and it is not a reason to undo the add.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The same rule, on the two paths that run autodiscovery before any account exists. They used
     */
    @Test fun `neither discovery path turns a cancelled probe into "no server found"`() {
        val offenders = discoveryPaths.filterNot { path ->
            val body = functionBody(path)
            RETHROWS_CANCELLATION.containsMatchIn(body) &&
                !DISCOVERY_RUN_CATCHING.containsMatchIn(body) &&
                "discoverJmapServer(" in body
        }
        assertEquals(
            "each discovery path must run discoverJmapServer() in a try that rethrows " +
                "CancellationException before its catch-all. runCatching{}.getOrElse{} cannot: it " +
                "catches the cancellation too, and the path then reports NotFound — NeedsServer on " +
                "a screen the user has already left.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * Offline, nothing typed on this screen can reach a server, and every route used to find that
     */
    @Test fun `every add route refuses to start a request while offline`() {
        val offenders = offlineGuarded.filterNot { OFFLINE_GUARD.containsMatchIn(functionBody(it)) }
        assertEquals(
            "each of these routes must open with the offline guard, spelled exactly, and at the " +
                "top level of the function — not nested inside any condition:\n" +
                "    if (!hasUsableNetwork(app)) {\n" +
                "        _state.value = ConnectState.Error(string(R.string.connect_offline))\n" +
                "        return\n" +
                "    }\n" +
                "A route without it spins on the full connect/read timeout in airplane mode before " +
                "reporting that nothing answered — for its protocol only, which is worse than no " +
                "guard at all because the screen then behaves differently per sign-in method.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * And it must come FIRST. A guard is not a guard once the request is in flight: placed after
     */
    @Test fun `the offline guard runs before anything is launched`() {
        val offenders = offlineGuarded.mapNotNull { route ->
            val body = functionBody(route)
            val guard = OFFLINE_GUARD.find(body)?.range?.first ?: return@mapNotNull null
            val handoff = requestHandoffs.map { body.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (handoff == null || guard < handoff) null else "$route: guard at $guard, handoff at $handoff"
        }
        assertEquals(
            "the offline guard must precede the first viewModelScope.launch / container. call of " +
                "its route: after them the probe is already running and the guard only decorates " +
                "the failure. It stays AFTER the busy()/Connecting anti-double-submit check — a " +
                "different rule, and one nothing in this module tests, so do not read this as " +
                "cover for it.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The same symptom, on the two routes that sign in an account an import brought in. They report
     */
    @Test fun `signing in an imported account refuses to start a request while offline`() {
        val offenders = offlineGuardedImports.filterNot { IMPORT_OFFLINE_GUARD.containsMatchIn(functionBody(it)) }
        assertEquals(
            "each imported-account sign-in must open with the offline guard in its own shape, at " +
                "the top level of the function:\n" +
                "    if (!hasUsableNetwork(app)) {\n" +
                "        updateSelected { it.copy(error = string(R.string.connect_offline)) }\n" +
                "        return\n" +
                "    }\n" +
                "Without it the row spins on the full connect/read timeout and then shows the raw " +
                "exception text — after an import, which is the offline case by construction.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The vocabulary must be alive, for the same reason `container.accountStore.addOAuth(` had to
     */
    @Test fun `every route this lint guards still exists in the file`() {
        val dead = (offlineGuarded + offlineGuardedImports)
            .filterNot { Regex("""\bfun $it\b""").containsMatchIn(source()) }
        assertEquals(
            "these routes no longer exist in ConnectViewModel, so the offline rules above are " +
                "reading nothing on their behalf. Renamed? update the name here. Gone? drop it — " +
                "never leave a name that cannot match.",
            emptyList<String>(), dead,
        )
    }

    @Test fun `no add path refreshes on its own`() {
        val offenders = addPaths.filter { REFRESH.containsMatchIn(functionBody(it)) }
        assertEquals(
            "an add path must not call refresh() itself: the credentials in scope there are the " +
                "ones it just built, and they carry no account id until the account exists (#121).",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `every add path proves the credentials before persisting anything`() {
        val offenders = addPaths.filterNot { path ->
            val body = functionBody(path)
            "validate = { container.mailRepository.testConnection(it).getOrThrow() }" in body
        }
        assertEquals(
            "each add path must validate with testConnection(), which authenticates WITHOUT writing " +
                "anything. Validating by priming the cache is the bug; not validating at all would " +
                "leave an account behind on a mistyped password.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * REWRITTEN. This test used to read two fragments of the token path's hand-written re-add
     */
    @Test fun `the token path leaves the re-add to the store instead of writing it by hand`() {
        val body = functionBody("finishTokenConnect")
        val offenders = body.lines().map { it.trim() }.filter { UPDATE_PASSWORD.containsMatchIn(it) }
        assertEquals(
            "finishTokenConnect must not update a password / set the current account itself: the " +
                "one call it makes (container.accountStore.add) refreshes the account already " +
                "stored under this identity and hands back ITS id, atomically. Doing it here " +
                "again writes the token twice, and the hand-written search it used to sit behind " +
                "filtered on authType — so re-adding by token an address added by password left " +
                "the reader with two rows for one mailbox. Found:\n" + offenders.joinToString("\n") +
                "\nBody was:\n$body",
            emptyList<String>(), offenders,
        )
    }

    /**
     * WHICH ACCOUNT IS ALREADY THERE is one shared decision (`resolveExistingLogin` +
     */
    @Test fun `each add path asks the shared identity whether the account is already there`() {
        val offenders = addGuards.filterNot { (path, required) ->
            functionBody(path).lines().map { it.trim() }.contains(required)
        }.map { (path, required) -> "$path: expected\n  $required" }
        assertEquals(
            "each add path must decide from the shared identity, before it goes to the network, " +
                "whether this add creates the account or lands on one already installed. Without " +
                "it the screen cannot tell the reader which of the two happened, and the sign-in " +
                "ends exactly as a first one does — on a phone that now shows one account where " +
                "she believes she added a second. Missing:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * And the answer must be USED. `created = true` written into `AddedAccount` is the state the
     */
    @Test fun `no add path declares itself the creator regardless of what it found`() {
        val offenders = addPaths.flatMap { path ->
            functionBody(path).lines().map { it.trim() }.filter { CREATED_TRUE.containsMatchIn(it) }
                .map { "$path: $it" }
        }
        assertEquals(
            "an add path must carry the answer it got (created = created), never a hard-coded " +
                "true: with true, a re-add is reported as a creation and the reader is told an " +
                "account was added that was already installed. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * And it must be SAID. The sentence itself is chosen by `accountAddedToast`, executed in
     * [AccountAddedToastTest] — these routes only call it.
     */
    @Test fun `each add path says which of the two happened`() {
        val offenders = sayingPaths.filterNot { path ->
            functionBody(path).lines().map { it.trim() }.contains(SAYS_IT)
        }
        assertEquals(
            "each add path must end on `$SAYS_IT`: the whole point of asking whether the account " +
                "was already there is telling the reader. Without this line a re-add succeeds in " +
                "silence, which is what made the same mailbox be added over and over. Missing " +
                "in:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * Sub-account reconciliation runs on a CREATION only, and this is the rule that keeps it
     */
    @Test fun `sub-account reconciliation only runs when the add created the account`() {
        val gated = "if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)"
        val offenders = listOf("finishTokenConnect", "finishJmapConnect").flatMap { path ->
            functionBody(path).lines().map { it.trim() }
                .filter { RECONCILE.containsMatchIn(it) && it != gated }
                .map { "$path: $it" }
        }
        assertEquals(
            "reconcileLinkedAccountsAfterAdd must sit behind `if (created)`. On a re-add it hands " +
                "the #129 diff a session of possibly the other auth family, over a login that " +
                "already has sub-accounts — and eviction there deletes a real shared mailbox with " +
                "its cache. Nothing is lost by the gate: the call is an optimisation and the " +
                "connect() hook reconciles anyway. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * The last stretch, which every one of the four routes above goes through. `accountAddedToast`
     */
    @Test fun `the toast helper shows the resource it was handed, and names none of its own`() {
        assertEquals(
            "ConnectViewModel.toast must show the resource accountAddedToast chose. Naming a " +
                "resource here, or dropping the call, is the same defect one floor down: a re-add " +
                "reported as \"Account added\", or said nothing about at all.",
            listOf(
                "private fun toast(@StringRes resId: Int) {",
                "Toast.makeText(app, string(resId), Toast.LENGTH_LONG).show()",
                "}",
            ),
            functionBody("toast").lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * And no route may name the re-add sentence itself. That is the reproach `OAuthWiringTest`
     */
    @Test fun `no add path picks the re-add sentence itself`() {
        val offenders = sayingPaths.flatMap { path ->
            functionBody(path).lines().map { it.trim() }.filter { REFRESHED_SENTENCE.containsMatchIn(it) }
                .map { "$path: $it" }
        }
        assertEquals(
            "R.string.connect_account_refreshed is one of the two answers accountAddedToast picks " +
                "between. Named inside a route, the choice moves back into a function nothing in " +
                "this module can execute — where \"Account added\" and \"already set up here\" can " +
                "be swapped without a single red test. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * SOURCE LINT — an account that was just added must have its inbox baseline WRITTEN by the add
     */
    @Test fun `priming an added account seeds its inbox baseline, last`() {
        assertEquals(
            "ConnectViewModel.primeInbox must end by seeding the inbox baseline of the account it " +
                "just primed:\n  seedInboxBaseline(credentials.id, meta.mailboxId)\n" +
                "Without it, the first mail arriving after an account is added is never announced " +
                "(the first background pass seeds silently). With the wrong argument, or seeded " +
                "before the refresh, it is the same defect with a green test.",
            listOf(
                "private suspend fun primeInbox(credentials: AccountCredentials) {",
                "val meta = container.mailRepository.refresh(credentials)",
                "container.accountStore.saveInboxMetaFor(",
                "credentials.id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount,",
                ")",
                "seedInboxBaseline(credentials.id, meta.mailboxId)",
                "}",
            ),
            codeLines(functionBody("primeInbox")),
        )
    }

    /**
     * SOURCE LINT, NEGATIVE SPACE — this screen writes ONE baseline, and there is no second.
     */
    @Test fun `this screen writes exactly one baseline, through the seeding helper`() {
        assertEquals(
            "ConnectViewModel seeds a baseline somewhere new, or with something other than the " +
                "decision's answer. There is exactly one seed point on this screen — " +
                "seedInboxBaseline, reached from primeInbox after a successful refresh — and it " +
                "writes what baselineForAddedInbox returned, never a list it picked itself.",
            listOf("NewMailNotifier.seed(app, accountId, mailboxId, baseline)"),
            codeLinesNaming(code(), "NewMailNotifier.seed("),
        )
    }

    /**
     * SOURCE LINT — and the seed goes through the decision, which is the part
     */
    @Test fun `the seeding helper asks the decision, and a null answer writes nothing`() {
        assertEquals(
            "ConnectViewModel.seedInboxBaseline must read the notifier's bounded read, ask " +
                "baselineForAddedInbox whether this add may write a baseline at all, and RETURN " +
                "on null. Any other shape either bypasses the decision or turns \"do not touch " +
                "this baseline\" into \"replace it with nothing\".",
            listOf(
                "private suspend fun seedInboxBaseline(accountId: String, mailboxId: String) {",
                "val read = container.mailRepository.notifyRead(accountId, mailboxId)",
                "val baseline = baselineForAddedInbox(read, NewMailNotifier.hasBaseline(app, accountId, mailboxId))",
                "?: return",
                "NewMailNotifier.seed(app, accountId, mailboxId, baseline)",
                "}",
            ),
            codeLines(functionBody("seedInboxBaseline")),
        )
        assertEquals(
            "baselineForAddedInbox is called from exactly one place — seedInboxBaseline. A second " +
                "caller is how a seed that skipped the decision gets into this file.",
            listOf("val baseline = baselineForAddedInbox(read, NewMailNotifier.hasBaseline(app, accountId, mailboxId))"),
            codeLinesNaming(code(), "baselineForAddedInbox(").filterNot { it.startsWith("internal fun ") },
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /**
     * The body of a function of [CONNECT_VIEW_MODEL], as text: from its declaration to the line
     */
    private fun functionBody(name: String): String {
        val lines = source().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ConnectViewModel: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    /**
     * The name of the function whose declaration is the nearest one at or above [line] — how a rule
     */
    private fun enclosingFunction(line: Int): String {
        val lines = source().lines()
        for (i in line downTo 0) {
            DECLARATION.find(lines[i])?.let { return it.groupValues[1] }
        }
        error("no function declaration above line ${line + 1} of ConnectViewModel — is the file still Kotlin?")
    }

    /**
     * The file's CODE, comments cut — the same helper [app.sterna.push.NotifyCandidatesWiringTest]
     */
    private fun code(): String = codeOf(source())

    /** [text]'s code lines, trimmed, comments and blank lines cut. */
    private fun codeLines(text: String): List<String> = codeOf(text).lines().map { it.trim() }

    /** The code lines of [source] naming [needle] — WHOLE lines, never a `contains`, which is
     *  blind to anything a mutation lengthens. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }.filter { needle in it }

    private fun codeOf(text: String): String = text.lines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun source(): String = CONNECT_VIEW_MODEL.readText()

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val CONNECT_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }

        val REFRESH = Regex("""mailRepository\.refresh\([^)]*\)""")

        /** Any `runCatching` wrapped around autodiscovery, however the line is spelled. */
        val DISCOVERY_RUN_CATCHING = Regex("""runCatching\s*\{[^}]*discoverJmapServer""")

        /**
         * The cancellation catch WITH its body. Pinning the header alone leaves
         */
        val RETHROWS_CANCELLATION = Regex(
            """catch \(cancelled: CancellationException\) \{[^}]*throw cancelled""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** A Kotlin function declaration, and the name it declares. */
        val DECLARATION = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        /**
         * The offline guard, as a whole: every one of its four lines pinned end to end, so that
         */
        val OFFLINE_GUARD = guardPattern(
            """_state\.value = ConnectState\.Error\(string\(R\.string\.connect_offline\)\)""",
        )

        /** The same guard on the import routes, which answer through the row, not the screen. */
        val IMPORT_OFFLINE_GUARD = guardPattern(
            """updateSelected \{ it\.copy\(error = string\(R\.string\.connect_offline\)\) \}""",
        )

        /**
         * The four-line guard around [answer], anchored at a method body's own indentation: 8
         * spaces for the `if` and its closing brace, 12 for the two lines inside it.
         */
        private fun guardPattern(answer: String) = Regex(
            """^ {8}if \(!hasUsableNetwork\(app\)\) \{[ \t]*\r?\n""" +
                """ {12}$answer[ \t]*\r?\n""" +
                """ {12}return[ \t]*\r?\n""" +
                """ {8}\}[ \t]*$""",
            RegexOption.MULTILINE,
        )

        /** The one thing an add path may prime with — see the test that reads it. */
        const val PRIME_LAMBDA = "prime = { primeInbox(it) }"

        /** How an add path says which of the two happened. Whole line, arguments included. */
        const val SAYS_IT = "accountAddedToast(created, null)?.let { toast(it) }"

        /** Forbidden tokens: a substring search is sound here — a longer line still carries them. */
        val CREATED_TRUE = Regex("""created = true""")
        val REFRESHED_SENTENCE = Regex("""\bR\.string\.connect_account_refreshed\b""")
        val UPDATE_PASSWORD = Regex("""container\.accountStore\.(updatePassword|setCurrent)\(""")

        /** Any call of the #129 reconcile, gated or not — the test compares the WHOLE line. */
        val RECONCILE = Regex("""reconcileLinkedAccountsAfterAdd\(""")
    }
}
