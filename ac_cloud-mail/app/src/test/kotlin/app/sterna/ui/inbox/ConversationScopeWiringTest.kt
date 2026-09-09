package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument as [app.sterna.ui.NavHostSourceRulesTest],
 */
class ConversationScopeWiringTest {

    // -- the unfold's side: one decision, on the recorded scope, read live -------------------

    @Test fun `the unfold's folders come from the recorded scope, through the shared decision`() {
        val body = body(CONVERSATION_EXPANSION, "folders")
        assertTrue(
            "ListScope.folders must derive the unfolded conversation's folders from its OWN recorded " +
                "fields through $SHARED_DECISION — the decision the chip's query is bound with. " +
                "Body was:\n$body",
            SHARED_DECISION in body && "viewedMailboxIds" in body && "sentMailboxes" in body,
        )
        assertTrue(
            "ListScope.folders must not hard-code an empty resolution: an unfold scoped to fewer " +
                "folders than the chip counted is exactly the defect. Body was:\n$body",
            "emptyList(" !in body,
        )
    }

    @Test fun `the unfold reads the scope it was given, and reads it live`() {
        val body = body(CONVERSATION_EXPANSION, "one")
        assertTrue(
            "the member stream must scope each thread through the ListScope it is handed " +
                "(scope.folders(...)), not resolve anything for itself. Body was:\n$body",
            "scope.folders(" in body,
        )
        assertTrue(
            "the member stream must OBSERVE the cache (read(...) returns a Flow): a reading taken " +
                "once cannot follow a chip that is recomputed on every write. Body was:\n$body",
            "read(" in body,
        )
    }

    @Test fun `the ViewModel feeds the stream the recorded scope and an observed query`() {
        val body = body(INBOX_VIEW_MODEL, "observeThreadMembers")
        assertTrue(
            "observeThreadMembers must hand $RECORDED — the scope the list on screen was built " +
                "with — to the member stream. Body was:\n$body",
            "scope = $RECORDED" in body,
        )
        assertTrue(
            "the members must come from the OBSERVED repository query, handed the thread and the " +
                "folders the stream resolved and nothing of this ViewModel's own making: a one-shot " +
                "read is the snapshot this replaces, and a lambda that answers from anything but " +
                "$MEMBER_QUERY(accountId, folders, threadKey) is a second resolution. Body was:\n$body",
            MEMBER_READ.containsMatchIn(body),
        )
        assertTrue(
            "observeThreadMembers must not fabricate the members it then draws: every flow here " +
                "comes from the repository or from the ViewModel's own state, never from a literal " +
                "built on the spot. Body was:\n$body",
            "flowOf(" !in body && "emptyList(" !in body && "emptySet(" !in body,
        )
        assertTrue(
            "observeThreadMembers must hand the stream the OBSERVED snooze table ($SNOOZE_SOURCE)): " +
                "the members are read from `emails` alone, so nothing in that query notices a " +
                "snooze starting or lapsing. Handed an empty set instead, every rule here and the " +
                "whole suite stay green while a snoozed child is listed under a row whose chip " +
                "excludes it — and a child snoozed from the selection bar comes back under the " +
                "reader's thumb the moment the mask goes down. Body was:\n$body",
            "snoozed = $SNOOZE_SOURCE" in body,
        )
        assertTrue(
            "the unfold must not re-read the selection: the folders it shows are the ones the list " +
                "was BUILT with, and the two differ for as long as a new pager is loading. " +
                "Body was:\n$body",
            RE_READ !in body,
        )
    }

    @Test fun `the queries the unfold is read through are readings of tables, not constants`() {
        // One notch below the rule above, and out of its reach: the ViewModel can be handing the
        // stream exactly the right calls while those calls answer nothing. Empty either of them out
        //
        // A source rule is a poor instrument for that and this one claims no more than it does:
        // both queries live in core:data, where they can be RUN against a real database, and that
        val members = body(MAIL_REPOSITORY, MEMBER_QUERY.substringAfter('.'))
            .substringAfter(" =", missingDelimiterValue = "")
        val ignored = listOf("accountId", "mailboxIds", "threadKey").filterNot { it in members }
        assertEquals(
            "$MEMBER_QUERY must use every argument it is given: a body that mentions none of them " +
                "is a constant wearing the signature of a query — which is exactly how the unfold " +
                "goes empty under a chip that still counts. Unused: $ignored",
            emptyList<String>(), ignored,
        )
        val snoozes = body(MAIL_REPOSITORY, SNOOZE_SOURCE.substringAfter('.').removeSuffix("("))
        assertTrue(
            "$SNOOZE_SOURCE) must observe the snooze table. Handing back a constant empty flow " +
                "leaves every rule here green and silently undoes the snooze half of the unfold.",
            !CONSTANT_EMPTY_FLOW.containsMatchIn(snoozes),
        )
    }

    // -- the recording ----------------------------------------------------------------------

    @Test fun `the scope is recorded exactly where the list is built, and nowhere else`() {
        val assignments = codeLines(INBOX_VIEW_MODEL).count { ASSIGNMENT.containsMatchIn(it) }
        assertEquals(
            "$RECORDED must have exactly ONE writer — the flow that feeds the pager. A second " +
                "writer is a second resolution wearing the same name.",
            1, assignments,
        )
        val scopes = body(INBOX_VIEW_MODEL, "sentScopes")
        assertTrue(
            "the writer must be sentScopes, the very flow whose value the chip counts over. " +
                "Body was:\n$scopes",
            ASSIGNMENT.containsMatchIn(scopes) && SENT_RESOLUTION in scopes,
        )
        assertEquals(
            "$SENT_RESOLUTION must be observed from exactly one place in the app module: one " +
                "resolution, read once, handed to both sides.",
            1,
            File(root, APP_SOURCES).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sumOf { f -> codeLines(f).count { SENT_RESOLUTION in it } },
        )
    }

    @Test fun `the recorded folders are the paging key's, not a fresh read of the selection`() {
        val scopes = body(INBOX_VIEW_MODEL, "sentScopes")
        assertTrue(
            "sentScopes must record the folders the PAGING KEY pages, so the unfold and the chip " +
                "are scoped to the same list. Body was:\n$scopes",
            "viewedMailboxIds(key)" in scopes && RE_READ !in scopes,
        )
        val viewed = body(INBOX_VIEW_MODEL, "viewedMailboxIds")
        assertTrue(
            "viewedMailboxIds must read the key it is given: taking the live selection instead is " +
                "the same second lookup, on the other argument. Body was:\n$viewed",
            "key.sel" in viewed && "selection.value" !in viewed,
        )
    }

    // -- the representative: read where it is DRAWN ------------------------------------------

    @Test fun `the members listed under a row exclude the representative that row draws`() {
        val screen = code(INBOX_SCREEN)
        val reads = Regex("""threadMembers\[""").findAll(screen).count()
        assertEquals(
            "InboxScreen must read the unfolded members ONCE, into a value the children and their " +
                "positions both come from: two reads are two answers. Read at:\n" +
                codeLines(INBOX_SCREEN).filter { "threadMembers[" in it }.joinToString("\n"),
            1, reads,
        )
        assertTrue(
            "that read must subtract the representative THE ROW ITSELF DRAWS (email.id) through " +
                "ConversationExpansion.membersBelow. The representative is a live value — the " +
                "newest message of the thread in the viewed folder(s), recomputed on every write — " +
                "so subtracting a copy of it taken when the row was unfolded draws the new " +
                "representative twice and drops the old one, under a chip that still counts right. " +
                "Read was:\n" + codeLines(INBOX_SCREEN).filter { "threadMembers[" in it }.joinToString("\n"),
            MEMBERS_BELOW.containsMatchIn(screen),
        )
    }

    @Test fun `nothing records the representative the unfolded list is subtracted with`() {
        val stream = body(CONVERSATION_EXPANSION, "members") + "\n" + body(CONVERSATION_EXPANSION, "one")
        assertTrue(
            "ThreadMemberStream must be given no representative: taking one is the snapshot this " +
                "whole path exists to remove, moved one notch down — the members became live and " +
                "the id subtracted from them stayed frozen at the tap. The subtraction belongs " +
                "where the representative is drawn. Body was:\n$stream",
            "representative" !in stream,
        )
        assertTrue(
            "InboxViewModel must keep no map of recorded representatives: it is a fourth source " +
                "for a value the row already holds, and a source more always ends up diverging. " +
                "Found:\n" + codeLines(INBOX_VIEW_MODEL).filter { "threadReps" in it }.joinToString("\n"),
            codeLines(INBOX_VIEW_MODEL).none { "threadReps" in it },
        )
    }

    // -- the mask over the unfolded rows -----------------------------------------------------

    @Test fun `a member hidden from the unfolded rows is hidden for the length of one call`() {
        val byHand = codeLines(INBOX_VIEW_MODEL).filter { BY_HAND.containsMatchIn(it) }
        assertEquals(
            "no path may raise or lift the unfolded rows' mask by hand. The mask hides a member " +
                "whose removal is still in flight from a LIVE reading; making its lifting a " +
                "caller's duty is how the selection bar's snooze lost a message for good — it " +
                "raised the mask, never lifted it, and the message came back into the chip's " +
                "count at its due date and never back under the row. Every hiding is scoped to " +
                "the call that justifies it ($SCOPED_HIDE). By hand at:\n" + byHand.joinToString("\n"),
            0, byHand.size,
        )
        val helper = body(INBOX_VIEW_MODEL, SCOPED_HIDE)
        assertTrue(
            "$SCOPED_HIDE must go through ThreadMemberMask, which owns the mask and is the only " +
                "thing that raises or lowers it. Body was:\n$helper",
            ".hiding(" in helper,
        )
        val hiding = body(CONVERSATION_EXPANSION, "hiding")
        assertTrue(
            "ThreadMemberMask.hiding must lower the mask in a finally: lowering it on the success " +
                "path only is a duty again, and a failed — or a merely local — op would leave the " +
                "grave standing. Body was:\n$hiding",
            "finally" in hiding,
        )
    }

    @Test fun `every path that takes a member off the unfolded rows wraps its call in the mask`() {
        // The rule above checks the wrapper EXISTS and goes through the mask; it says nothing about
        // it being used. Unwrap a single caller — leave the op bare — and everything stays green
        val missing = MASKED_PATHS.map { it.first }
            .filterNot { "$SCOPED_HIDE(" in body(INBOX_VIEW_MODEL, it) }
        assertEquals(
            "these InboxViewModel paths remove a member from the unfolded rows without wrapping " +
                "the call in $SCOPED_HIDE: the cache row outlives the gesture, and the live reading " +
                "puts the child straight back under the thumb",
            emptyList<String>(), missing,
        )
        val calls = callArguments(code(INBOX_VIEW_MODEL), SCOPED_HIDE)
        assertEquals(
            "$SCOPED_HIDE has ${MASKED_PATHS.size} call sites " +
                "(${MASKED_PATHS.joinToString { it.first }}) and this counts them, because the rule " +
                "above cannot tell a wrapper that is called from one that merely exists. A new " +
                "masked path is welcome — add it to MASKED_PATHS. One that DISAPPEARED is the " +
                "defect. Masked with:\n" + calls.joinToString("\n"),
            MASKED_PATHS.size, calls.size,
        )
    }

    @Test fun `each masked path hides the very messages it is taking away`() {
        // And the rule above cannot see the ARGUMENT. Hand one of those calls an empty set — the
        // wrapper is still there, still called, still counted — and the mask covers nothing on the
        // one path it was written for: the child comes straight back under the reader's thumb for
        // the length of the network call. So each path says which messages it masks, by name.
        MASKED_PATHS.forEach { (path, expected) ->
            val body = body(INBOX_VIEW_MODEL, path)
            val argument = callArguments(body, SCOPED_HIDE).singleOrNull()
            assertEquals(
                "$path must raise the mask exactly once. Body was:\n$body",
                1, callArguments(body, SCOPED_HIDE).size,
            )
            assertTrue(
                "$path masks `$argument`, which is not the set of messages it is taking away " +
                    "(expected to match ${expected.pattern}). An empty — or narrower — set leaves " +
                    "the wrapper in place and the mask covering nothing, which every other rule " +
                    "here reads as correct.",
                expected.containsMatchIn(argument.orEmpty()),
            )
        }
    }

    // -- the swipe context a conversation opens with ------------------------------------------

    @Test fun `opening a message inside a conversation records the order the row is showing`() {
        // The CALL SITE, which is all this rule can see. What the call then does with the order is
        // [ThreadOrders]'s, and is pinned by running it (ThreadMemberStreamTest): this rule alone
        val screen = code(INBOX_SCREEN)
        val calls = Regex("""${Regex.escape(RECORD_ORDER)}\(""").findAll(screen).count()
        assertEquals(
            "InboxScreen must record the unfolded conversation's order at the tap, exactly once — " +
                "it is the only place holding both the representative the row draws and the members " +
                "drawn beneath it. Found:\n" +
                codeLines(INBOX_SCREEN).filter { "$RECORD_ORDER(" in it }.joinToString("\n"),
            1, calls,
        )
        assertTrue(
            "the recorded order must be the row's OWN representative (email) plus the members it " +
                "is drawing (members, the single subtracted read) — rebuilding it from anything " +
                "else is the remembered representative this path exists to remove. Call was:\n" +
                codeLines(INBOX_SCREEN).filter { "$RECORD_ORDER(" in it }.joinToString("\n"),
            RECORDED_ORDER.containsMatchIn(screen),
        )
    }

    @Test fun `the recorded order is kept by the store that is tested, and read back from it`() {
        // The other end of the same wiring: the ViewModel may build the order, but it must not be
        // the one KEEPING it. Both halves live in [ThreadOrders] — a plain class, instantiable,
        // exercised — so that dropping the keeping is a red test rather than a silent regression.
        val record = body(INBOX_VIEW_MODEL, "recordThreadOrder")
        assertTrue(
            "recordThreadOrder must delegate to $ORDER_STORE.record(...): building the entries here " +
                "and remembering them in a private map is exactly the split that went untested — " +
                "the building was pinned, the remembering was not. Body was:\n$record",
            "$ORDER_STORE.record(" in record,
        )
        val read = body(INBOX_VIEW_MODEL, "threadEntries")
        assertTrue(
            "threadEntries must answer from $ORDER_STORE.entries(...) — the same store the tap " +
                "wrote to, and nothing else. Body was:\n$read",
            "$ORDER_STORE.entries(" in read,
        )
        assertTrue(
            "the swipe context must be forgotten with the conversation it belongs to " +
                "($ORDER_STORE.drop / .clear), or a folded row keeps handing the reader an order " +
                "the list no longer shows. Found:\n" +
                codeLines(INBOX_VIEW_MODEL).filter { "$ORDER_STORE." in it }.joinToString("\n"),
            codeLines(INBOX_VIEW_MODEL).any { "$ORDER_STORE.drop(" in it } &&
                codeLines(INBOX_VIEW_MODEL).any { "$ORDER_STORE.clear()" in it },
        )
    }

    // -- the chip's side ----------------------------------------------------------------------

    @Test fun `the chip binds the Sent folders the shared decision hands it`() {
        // The other half of the same wiring, and just as free to drift: ConversationScopeTest
        // resolves the pairs itself before running the shipped SQL, so it would stay green if the
        // query went back to deriving its own.
        val body = body(MAIL_REPOSITORY, "conversationQuery")
        assertTrue(
            "conversationQuery must take its Sent pairs from ${SHARED_SENT} — the same decision " +
                "the unfold is scoped by. Body was:\n$body",
            SHARED_SENT in body,
        )
        assertTrue(
            "conversationQuery must not derive its own Sent scope beside the shared one. " +
                "Body was:\n$body",
            "sentMailboxes.distinct()" !in body && "sentMailboxes.filter" !in body,
        )
    }

    @Test fun `every pager is built from the resolution the chip is counted over`() {
        // The mutation the unfold's rules cannot see: hand the PAGER an empty Sent scope and the
        // divergence comes back the other way round — an unfold of 3 under a chip of 2 — with every
        // other rule here satisfied. The repository's parameter has no default precisely so that
        // omitting it does not compile; this pins the other way of saying nothing.
        val body = body(INBOX_VIEW_MODEL, "pagedEmails")
        assertTrue(
            "pagedEmails must resolve the Sent scope through sentScopes(...) — the flow that also " +
                "records it for the unfold. Body was:\n$body",
            "sentScopes(" in body,
        )
        assertTrue(
            "every pager must be given that resolution and nothing else. Body was:\n$body",
            "conversationView, sent)" in body,
        )
        assertEquals(
            "exactly three pagers, one per Sel variant — a folder, the unified inbox, the unread " +
                "scope — all fed the same way. A fourth selection added without a Sent scope of its " +
                "own is a chip that counts fewer messages than the row unfolds into. Body was:\n$body",
            3,
            Regex("""repo\.paged\w+\(""").findAll(body).count(),
        )
        assertTrue(
            "pagedEmails must not hand a pager an empty Sent scope: the chip would then count " +
                "fewer messages than the row unfolds into. Body was:\n$body",
            "emptyList(" !in body,
        )
    }

    // -- reading the sources ----------------------------------------------------------------

    /**
     * The lines of [file] that are code, WITH THEIR COMMENTS TAKEN OFF: a line whose first non-blank
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /**
     * [line] up to its first `//` that is not inside a double-quoted string — the whole line when
     * there is none. `\` escapes the next character, so a `\"` inside a string does not end it.
     */
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

    /**
     * [file]'s code as ONE string. Rules match over this rather than line by line: a call that fits
     */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The argument texts of every call to [name] in [text], parentheses balanced — so `f(a) { … }`
     */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""(?<!fun <T> )${Regex.escape(name)}\(""").findAll(text).map { match ->
            val start = match.range.last + 1
            var depth = 1
            var i = start
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            text.substring(start, (i - 1).coerceAtLeast(start)).trim()
        }.toList()

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        // `fun <T> name(...)`: the type parameters sit between the keyword and the name.
        val declaration = Regex("""\b(fun|val|var)\s+(<[^>]*>\s+)?$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var open = false
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (open && i > start && line.indentWidth() <= indent) break
            out += line
            val code = line.trimEnd()
            // A one-line expression body is complete on its declaration: its parentheses are
            // already balanced, so the next declaration ends it instead of the whole file doing so.
            if (code.endsWith("{") || code.endsWith("=") ||
                (i == start && code.count { it == '(' } == code.count { it == ')' })
            ) {
                open = true
            }
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        /** The scope the list on screen was built with, and the only one the unfold may use. */
        private const val RECORDED = "listScope"

        /** Its single writer, matched as an assignment rather than a mention. */
        private val ASSIGNMENT = Regex("""\b$RECORDED\.value\s*=[^=]""")

        /** The one resolution both sides descend from. */
        private const val SENT_RESOLUTION = "observeSentMailboxes"

        /** Reading the live selection instead of the value the list was built with. */
        private const val RE_READ = "currentMailboxIds("

        private const val SHARED_DECISION = "ConversationScope.folders("
        private const val SHARED_SENT = "ConversationScope.sentFolders("

        /** The row's own representative, subtracted from the members at the place both are drawn.
         *  Whitespace-tolerant throughout (`\s` spans newlines): the call is one indent away from
         *  being wrapped by the formatter, and a correct reformat must not redden a rule. */
        private val MEMBERS_BELOW = Regex(
            """membersBelow\(\s*threadMembers\[\s*threadKey\s*]\.orEmpty\(\)\s*,\s*email\.id\s*,?\s*\)""",
        )

        /** The observed cache query the member stream is read through. */
        private const val MEMBER_QUERY = "repo.observeThreadEmails"

        /** And the shape of the hand-over: the stream's own arguments, straight through. A lambda
         *  that resolves its own folders — or answers a flow of its own — is the second resolution
         *  this whole file exists to forbid. */
        private val MEMBER_READ = Regex(
            """read\s*=\s*\{\s*accountId\s*,\s*folders\s*,\s*threadKey\s*->\s*""" +
                """${Regex.escape(MEMBER_QUERY)}\(\s*accountId\s*,\s*folders\s*,\s*threadKey\s*,?\s*\)""",
        )

        /** The observed snooze table the member stream must be fed — the chip's SQL excludes an
         *  active snooze, and the unfold has to see it start AND lapse. */
        private const val SNOOZE_SOURCE = "repo.observeActiveSnoozed("

        /** A flow that answers a constant nothing — the shape an emptied-out query takes. */
        private val CONSTANT_EMPTY_FLOW = Regex("""flowOf\(\s*empty\w*\(\s*\)\s*\)""")

        /** The only form allowed to hide a member from the unfolded rows. */
        private const val SCOPED_HIDE = "hidingThreadMembers"

        /**
         * The paths that take a member off the unfolded rows, and must therefore mask it — the
         */
        private val MASKED_PATHS: List<Pair<String, Regex>> = listOf(
            // Every message whose row it evicts.
            "heldBackDestroy" to Regex("""^emails\b[\s\S]*\bemailKey\(\)"""),
            // The one message the swipe flew away.
            "swipeRemove" to Regex("""^setOf\(\s*email\.emailKey\(\)\s*,?\s*\)$"""),
            // The whole selection the batch is about to act on.
            "bulk" to Regex("""^keys$"""),
            "bulkBatched" to Regex("""^targetKeys$"""),
            // The one message leaving for another account (#189), and each of a selection in turn.
            "moveToAccount" to Regex("""^setOf\(\s*key\s*,?\s*\)$"""),
            "moveSelectedToAccount" to Regex("""^setOf\(\s*key\s*,?\s*\)$"""),
        )

        /** The store that keeps the swipe context — out of the ViewModel so it can be run. */
        private const val ORDER_STORE = "threadOrder"

        /** The swipe context of a message opened from inside an unfolded conversation. */
        private const val RECORD_ORDER = "viewModel.recordThreadOrder"

        /** Recorded from what the row is DRAWING: its own representative and the members below. */
        private val RECORDED_ORDER = Regex(
            """${Regex.escape(RECORD_ORDER)}\(\s*threadKey\s*,\s*email\s*,\s*members\s*,?\s*\)""",
        )

        /** Raising or lifting that mask outside [SCOPED_HIDE] — the shape a caller can forget. */
        private val BY_HAND = Regex("""\b(removedMembers|restoreThreadMembers|dropThreadMembers?)\b""")

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val CONVERSATION_EXPANSION_PATH = "$APP_SOURCES/app/sterna/ui/inbox/ConversationExpansion.kt"
        private const val REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val CONVERSATION_EXPANSION: File by lazy { File(root, CONVERSATION_EXPANSION_PATH) }
        private val MAIL_REPOSITORY: File by lazy { File(root, REPOSITORY_PATH) }
    }
}
