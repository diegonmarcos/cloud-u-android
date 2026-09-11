package app.sterna.ui.inbox

import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STARRING IS A SERVER-SIDE FLAG, AND "STARRED" IS A QUERY OVER IT — NOT A FOLDER.
 *
 * The owner asked whether a star in the app is local metadata or reaches the server, and asked for
 * a Starred folder. It reaches the server; this pins the three ways that could quietly stop being
 * true, and the one way the Starred view could quietly become destructive.
 *
 * ⛔ WHY THERE IS NO "STARRED" MAILBOX. `$flagged` is a KEYWORD on a message (RFC 8621 §4.1.1) and
 * `\Flagged` a message flag (RFC 3501 §2.3.2) — an ATTRIBUTE, not a container. A real Mailbox named
 * "Starred" would show up in every other client on the account, and anything that then MOVED mail
 * into it would take that mail OUT of the folder it belongs in — the #67 shape of bug, and data
 * loss from the reader's side. The drawer entry is a saved search, exactly like Gmail's.
 *
 * Every source-reading assertion here runs on COMMENT-STRIPPED lines, because the production code
 * this guards explains itself in prose that contains every string being searched for — "Starred",
 * "$flagged", "Email/query". Matching the KDoc that documents a call instead of the call is a false
 * green this repository has shipped before.
 */
class StarredIsAKeywordNotAMailboxTest {

    // ── the flag itself ───────────────────────────────────────────────────────────────────────

    /**
     * BEHAVIOUR, not source. A search whose only criterion is "flagged" must not read as empty, or
     * `SearchViewModel.run` short-circuits to `Idle` and the drawer's Starred entry opens on a
     * blank screen — the exact "lists nothing and passes" failure, with no crash to notice.
     */
    @Test fun `flagged alone is a real search, so the Starred view actually runs`() {
        assertFalse(
            "a query carrying only `flagged` reads as empty, so Starred would open on nothing",
            SearchQuery(flagged = true).isEmpty(),
        )
        assertTrue("a query carrying nothing must still read as empty", SearchQuery().isEmpty())
    }

    /**
     * The keyword strings, PINNED AS LITERALS typed out here rather than imported from the code
     * that uses them — reading the constant the production code reads proves only that a string
     * equals itself. A typo (`$Flagged`, `flagged`) would be ACCEPTED by the server as a private
     * keyword, stay invisible to every other client, and look exactly like success until the owner
     * opens webmail.
     */
    @Test fun `the star names the protocol flag byte for byte, on both protocols`() {
        val body = setFlaggedBody()
        // The SOURCE TEXT being looked for, character by character, because that is what is read:
        //   JMAP → the eleven characters  " \ $ f l a g g e d "   (the `\` is Kotlin's escape for
        //          the `$`, so the string's VALUE is the IANA keyword `$flagged`).
        //   IMAP → the eleven characters  " \ \ F l a g g e d "   (value: the system flag `\Flagged`).
        val jmapKeyword = "\"\\\$flagged\""
        val imapFlag = "\"\\\\Flagged\""
        assertTrue(
            "the JMAP leg no longer writes the IANA keyword \$flagged (RFC 8621 §4.1.1) as " +
                "$jmapKeyword. Body:\n" + body.joinToString("\n"),
            body.any { jmapKeyword in it },
        )
        assertTrue(
            "the IMAP leg no longer writes the \\Flagged system flag (RFC 3501 §2.3.2) as " +
                "$imapFlag. Body:\n" + body.joinToString("\n"),
            body.any { imapFlag in it },
        )
    }

    /**
     * The answer to the owner's question, guarded. A star must leave the device: if `setFlagged`
     * ever writes only `emailDao`, starring silently becomes local metadata again and nothing else
     * in the suite would notice — asserting that the Room row got the flag proves only that local
     * storage works, which is the very thing in question.
     */
    @Test fun `a star is written to the server, not only to the local row`() {
        val body = setFlaggedBody()
        assertTrue(
            "the JMAP leg no longer issues `Email/set` for the keyword. Body:\n${body.joinToString("\n")}",
            body.any { "client.setKeyword(" in it },
        )
        assertTrue(
            "the IMAP leg no longer issues `STORE`. Body:\n${body.joinToString("\n")}",
            body.any { "imap.setFlag(" in it },
        )
    }

    // ── the drawer entry ──────────────────────────────────────────────────────────────────────

    /**
     * The Starred row must sit BELOW the "Unread" view entry and ABOVE the All|Unread `TabRow`.
     *
     * Both halves matter. Below Unread puts it last of the views and so directly against the folder
     * list, where Gmail puts Starred. Above the TabRow is what makes its behaviour under the Unread
     * tab well-defined: the tab narrows only `drawnFolders`, the folder rows drawn beneath it, so a
     * row above it is never hidden by either tab. Starred has no local count to be judged on, so
     * "hide it when its count is zero" is not a question this drawer can even ask.
     */
    @Test fun `Starred is last of the views and above the folder tabs, so no tab can hide it`() {
        val lines = codeLines(INBOX_SCREEN)
        val unreadView = lines.indexOfFirst { "selected = ui.unreadView" in it }
        val starred = lines.indexOfFirst { "R.string.folder_flagged" in it }
        val tabs = lines.indexOfFirst { "TabRow(selectedTabIndex = folderTab.ordinal" in it }
        assertTrue("the Unread view entry is gone from the drawer", unreadView >= 0)
        assertTrue("the Starred entry is gone from the drawer", starred >= 0)
        assertTrue("the All|Unread tabs are gone from the drawer", tabs >= 0)
        assertTrue("Starred no longer sits below the Unread view entry", unreadView < starred)
        assertTrue("Starred fell below the folder tabs, where a tab can hide it", starred < tabs)
    }

    /**
     * Starred creates nothing and moves nothing. The entry navigates to the search screen on the
     * one criterion; if a mailbox id, a folder selection or a mailbox creation ever appears on that
     * path, the row has stopped being a saved search and started being a folder.
     */
    @Test fun `tapping Starred selects no folder and creates no mailbox`() {
        val onClick = starredOnClick()
        assertEquals(
            "the Starred entry's onClick is no longer just `open the view, close the drawer`: $onClick",
            listOf("onOpenStarred()", "scope.launch { drawerState.close() }"),
            onClick,
        )
        val route = codeLines(STERNA_APP).first { "onOpenStarred =" in it }
        assertTrue(
            "the Starred route carries a mailbox id — it is navigating to a FOLDER: $route",
            "mailbox" !in route.lowercase(),
        )
        assertTrue(
            "the Starred route creates a mailbox on the server: $route",
            "create" !in route.lowercase(),
        )
    }

    /**
     * `folderRank` must never learn the `flagged` role. Ranking Starred would mean synthesising a
     * fake `Mailbox` to rank — the fake being one refactor away from something that gets created.
     * The row is a destination like Home, and destinations are not ranked among folders.
     */
    @Test fun `the folder ordering never learns a flagged rank`() {
        assertEquals(
            "`flagged` gained a folder rank, so something is treating Starred as a mailbox",
            folderRank(null),
            folderRank("flagged"),
        )
    }

    /**
     * NO BADGE, and this is the #247 rule, not a cosmetic one. Every number in this drawer comes
     * from the local Room mirror at ZERO network per row. Starred's content is resolved by the
     * SERVER, so any count drawn on it either disagrees with the list it labels or costs a network
     * call in a list-item bind. Neither is allowed; no count is the honest answer.
     */
    @Test fun `the Starred row draws no count, so it costs no network to show`() {
        val row = starredRow()
        val offenders = row.filter { line ->
            listOf("badge", "unread", "count").any { it in line.lowercase() }
        }
        assertEquals(
            "the Starred drawer row gained a count. Its content comes from the server, so the " +
                "number is either wrong or paid for with a network call per drawer open:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * THE OTHER HALF OF THE HAND-OFF, and the quietest way this feature could ship broken.
     *
     * The drawer navigates to `search?flagged=true`. If the route does not DECLARE that argument,
     * Navigation drops it silently: the screen opens, the criteria panel is blank, the result area
     * is empty, and Starred reads as "you have no starred mail". Nothing crashes and nothing else
     * in this suite notices — the row is still there, still navigating, still creating no mailbox.
     */
    @Test fun `the search route declares the flagged argument the drawer sends`() {
        val app = codeLines(STERNA_APP)
        assertTrue(
            "the search route no longer declares `flagged`, so the drawer's argument is dropped " +
                "and Starred opens on an empty form that looks like an empty mailbox",
            app.any { it.contains("route = \"search?q={q}&from={from}&flagged={flagged}\"") },
        )
        assertTrue(
            "`flagged` must be declared as a navArgument as well as named in the route pattern",
            app.any { it.contains("navArgument(\"flagged\")") },
        )
    }

    /**
     * And the screen must RUN the search, not merely pre-tick the switch. A Starred entry that
     * lands on a filled-in form with no results is the "lists nothing and passes" failure wearing
     * a different hat.
     */
    @Test fun `arriving on the flagged argument runs the search`() {
        val vm = codeLines(SEARCH_VIEW_MODEL)
        assertTrue(
            "SearchViewModel must read SEARCH_FLAGGED_ARG into the flagged criterion",
            vm.any { it.contains("handle[SEARCH_FLAGGED_ARG]") },
        )
        assertTrue(
            "arriving on SEARCH_FLAGGED_ARG must RUN the search, not just fill the form",
            vm.any { it.contains("arrivedPreRun()") },
        )
        assertTrue(
            "the pre-run guard must test KEY_SUBMITTED for ABSENCE (`== null`). Written `!= true`, " +
                "emptying the criteria by hand would put the starred results back on the next " +
                "recreation, under a form the reader had just deliberately cleared",
            vm.any { it.contains("handle.get<Boolean>(KEY_SUBMITTED) == null") },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** The body of `MailRepository.setFlagged`, comments stripped — up to the next declaration. */
    private fun setFlaggedBody(): List<String> {
        val lines = codeLines(MAIL_REPOSITORY)
        val start = lines.indexOfFirst { it.startsWith("suspend fun setFlagged(") }
        check(start >= 0) { "cannot find `suspend fun setFlagged(` in ${MAIL_REPOSITORY.path}" }
        val body = lines.drop(start + 1).takeWhile { !it.startsWith("suspend fun ") && !it.startsWith("fun ") }
        check(body.isNotEmpty()) { "`setFlagged` has an empty body — the scan is broken, not the code" }
        return body
    }

    /** The Starred `NavigationDrawerItem` block, comments stripped: its label line and what follows. */
    private fun starredRow(): List<String> {
        val lines = codeLines(INBOX_SCREEN)
        val label = lines.indexOfFirst { "R.string.folder_flagged" in it }
        check(label >= 0) { "cannot find the Starred drawer row in ${INBOX_SCREEN.path}" }
        // Back up to the row's opening call, then take to its close.
        val open = lines.take(label).indexOfLast { it == "NavigationDrawerItem(" }
        check(open >= 0) { "the Starred label is not inside a NavigationDrawerItem(" }
        val block = lines.drop(open).takeWhile { it != ")" }
        check(block.size > 1) { "the Starred row block did not close — the scan is broken" }
        return block
    }

    /** The statements inside the Starred entry's `onClick = { … }`. */
    private fun starredOnClick(): List<String> {
        val row = starredRow()
        val open = row.indexOfFirst { it.startsWith("onClick = {") }
        check(open >= 0) { "the Starred row has no onClick" }
        return row.drop(open + 1).takeWhile { it != "}," }
    }

    /**
     * [file]'s non-blank lines with every comment taken out, whitespace collapsed. String literals
     * are tracked so a `//` or `/*` inside one is not mistaken for a comment.
     */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            val collapsed = code.toString().replace(Regex("""\s+"""), " ").trim()
            if (collapsed.isNotBlank()) out += collapsed
        }
        return out
    }

    companion object {
        private val INBOX_SCREEN: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        }
        private val STERNA_APP: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/ui/SternaApp.kt")
        }
        private val MAIL_REPOSITORY: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }
        private val SEARCH_VIEW_MODEL: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/ui/search/SearchViewModel.kt")
        }

        /** Fails closed: an unlocatable source file aborts rather than passing on an empty scan. */
        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads source " +
                        "files as text and needs a working directory inside the checkout",
                )
    }
}
