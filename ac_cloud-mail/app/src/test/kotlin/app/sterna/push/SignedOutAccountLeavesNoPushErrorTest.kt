package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — and here that is not a shortcut, it is the whole reachable
 */
class SignedOutAccountLeavesNoPushErrorTest {

    @Test fun `the pass drops a signed-out account instead of reporting it as a failure`() {
        // Pinned as a block: the arm has to SWALLOW. A `throw gone` or a `Result`-shaped rethrow
        // slipped between the two lines puts the error entry and the two retries straight back.
        //
        // And WHAT the line claims is not decided here. The guard throws when the pass's id is
        // not in `AccountStore.accounts()`, which answers an UNREADABLE blob with an empty list —
        assertEquals(
            "the AccountGoneException arm of FetchAndNotify.run is no longer, line for line, what " +
                "this was written against. Either it no longer asks accountGoneCause what " +
                "happened (and AccountGoneCauseTest then proves nothing about the app), or it no " +
                "longer reads accountsUnreadable() afresh, or it is back to claiming a sign-out " +
                "for account data it merely failed to read.",
            listOf(
                "} catch (gone: AccountGoneException) {",
                "android.util.Log.i(",
                "\"FetchAndNotify\",",
                "\"background pass: \${accountGoneCause(credentials.id, store.accountsUnreadable(), " +
                    "passKind = \"pass\")}, dropped\",",
                "gone,",
                ")",
                "}",
            ),
            block("run", ARM, 7),
        )
    }

    @Test fun `nothing catches ahead of it — AccountGoneException IS a CancellationException`() {
        // Not cosmetic. The refusal is a cancellation by design, so ANY arm above this one that
        // names CancellationException (or Throwable) takes it first and the dedicated arm becomes
        //
        // This filter is what carries the ordering constraint. There is deliberately no
        // `catch (c: CancellationException) { throw c }` under the arm below (nothing catches
        // Throwable in this function, so it would be a no-op line), which means the order is
        // stated HERE or nowhere.
        //
        // Asked PER FUNCTION since the second arm landed. `ARM` is one constant and the two arms
        // are spelled identically, so a whole-file `catches.first()` would state the order for
        // `run` twice and for `onInboxRefreshed` never — and an arm slipped above the second one
        // would be invisible while its own test still found its block further down.
        listOf("run", "onInboxRefreshed").forEach { function ->
            val catches = lines(function).filter { CATCH.containsMatchIn(it) }
            assertTrue(
                "FetchAndNotify.$function no longer carries the AccountGoneException arm at all; " +
                    "its catch arms are:\n" + catches.joinToString("\n"),
                ARM in catches,
            )
            assertEquals(
                "another catch arm now sits ABOVE the AccountGoneException one in " +
                    "FetchAndNotify.$function. AccountGoneException extends CancellationException, " +
                    "so whatever that arm names swallows the sign-out first and the dedicated arm " +
                    "never runs.",
                ARM, catches.first(),
            )
        }
    }

    @Test fun `the guarded call really sits inside the try the arm closes`() {
        // An arm that encloses nothing is worse than no arm: the log reads "handled" and the
        // exception still climbs out of the caller that actually made the network call.
        val body = lines("run")
        val opened = body.indexOf("try {")
        val call = body.indexOf("val refreshes = container.mailRepository.refreshAccountFolders(")
        val arm = body.indexOf(ARM)
        assertTrue(
            "FetchAndNotify.run no longer opens a try, or no longer calls refreshAccountFolders " +
                "the way this was written against:\n" + body.joinToString("\n"),
            opened >= 0 && call >= 0 && arm >= 0,
        )
        assertTrue("refreshAccountFolders is now called outside the try", opened < call)
        assertTrue("the arm no longer closes a try that is open at the call", call < arm)
        // The un-archive is the SECOND thing in that try that can refuse (its own two guards, added
        // for the path #121 never walked), and it is deeper in: inside the per-folder forEach.
        val unarchive = body.indexOf(UNARCHIVE)
        assertTrue(
            "FetchAndNotify.run no longer calls unarchiveThreadsOnReply the way this was written " +
                "against:\n" + body.joinToString("\n"),
            unarchive >= 0,
        )
        assertTrue("unarchiveThreadsOnReply is now called outside the try", opened < unarchive)
        assertTrue("the arm no longer closes a try that is open at the un-archive", unarchive < arm)
    }

    // -- the un-archive: a refusal that has to LEAVE the runCatching wrapping it ---------------------

    @Test fun `the background pass lets the un-archive's refusal out of its best-effort wrapper`() {
        // The `runCatching` is not the defect and must stay: a move the server refuses must not
        // cost the notification. What it must not do is eat the sign-out, because the guards in
        //
        // Pinned as a block: `.rethrowIfCancelled()` deleted, or moved after `.getOrDefault(...)`
        // where it can never see the exception, leaves the two guards next door doing nothing at
        // all — with SignedOutAccountWiringTest still green, since it only reads MailRepository.kt.
        assertEquals(
            "the un-archive call of FetchAndNotify.run is no longer, line for line, what this was " +
                "written against. Back to a bare .getOrDefault(emptyList()), the sign-out refusal " +
                "is swallowed with the ordinary move failures and the pass writes a notifier " +
                "baseline under an account that no longer exists.",
            listOf(
                UNARCHIVE,
                ".rethrowIfCancelled()",
                ".getOrDefault(emptyList())",
            ),
            block("run", UNARCHIVE, 3),
        )
    }

    @Test fun `the foreground inbox hook drops the signed-out account on the spot`() {
        // The same wrapper, in the hook the UI calls after it refreshed an inbox itself. Not for
        // the caller's sake: `InboxViewModel` wraps both of its calls to this hook in a
        //
        // The claim is [app.sterna.core.data.mail.accountGoneCause]'s, executed by
        // `AccountGoneCauseTest`, and what is pinned here is that this site ASKS it, with a FRESH
        assertEquals(
            "the AccountGoneException arm of FetchAndNotify.onInboxRefreshed is no longer, line " +
                "for line, what this was written against. Either it stopped asking " +
                "accountGoneCause what happened, or it no longer reads accountsUnreadable() " +
                "afresh, or it borrowed the background pass's word for a foreground refresh.",
            listOf(
                ARM,
                "android.util.Log.i(",
                "\"FetchAndNotify\",",
                "\"foreground inbox: \${accountGoneCause(credentials.id, " +
                    "container.accountStore.accountsUnreadable(), passKind = \"refresh\")}, dropped\",",
                "gone,",
                ")",
                "}",
            ),
            block("onInboxRefreshed", ARM, 7),
        )
    }

    @Test fun `the foreground hook's arm encloses the un-archive AND everything it would feed`() {
        // What must stop is the REST of the hook: `notifyDiff` and `seed` both write the shared
        // notifier baseline under credentials.id, and an arm that only wrapped the call itself
        assertEquals(
            "the un-archive call of FetchAndNotify.onInboxRefreshed is no longer, line for line, " +
                "what this was written against. Without .rethrowIfCancelled() the runCatching eats " +
                "the sign-out (AccountGoneException IS a CancellationException), the arm below " +
                "never runs, and the hook writes its baseline under the removed account anyway.",
            listOf(
                UNARCHIVE_HOOK,
                ".rethrowIfCancelled()",
                ".getOrDefault(emptyList())",
            ),
            block("onInboxRefreshed", UNARCHIVE_HOOK, 3),
        )
        val body = lines("onInboxRefreshed")
        val opened = body.indexOf("try {")
        val call = body.indexOf(UNARCHIVE_HOOK)
        val notify = body.indexOfFirst { it.startsWith("NewMailNotifier.notifyDiff(") }
        val seed = body.indexOfFirst { it.startsWith("NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)") }
        val arm = body.indexOf(ARM)
        assertTrue(
            "FetchAndNotify.onInboxRefreshed no longer opens a try, or no longer calls " +
                "unarchiveThreadsOnReply / notifyDiff / seed the way this was written against:\n" +
                body.joinToString("\n"),
            opened >= 0 && call >= 0 && notify >= 0 && seed >= 0 && arm >= 0,
        )
        assertTrue("unarchiveThreadsOnReply is now called outside the try", opened < call)
        assertTrue(
            "notifyDiff now runs outside the try: a refused re-file no longer stops the baseline " +
                "write that follows it, under the account that was just removed",
            call < notify && notify < arm,
        )
        assertTrue(
            "the silent seed now runs outside the try: same baseline, same removed id, same ghost",
            call < seed && seed < arm,
        )
    }

    @Test fun `nothing else is open around the guarded calls that could swallow the refusal`() {
        // THE rule this file was missing, and a counter-expertise proved it by mutation: every
        // other rule here is about PRESENCE and ORDER, and both survive an enclosure. Wrap the
        //
        // So this asks what `SignedOutAccountWiringTest.openBlocksAt` asks of MailRepository, on
        // the file that one cannot see. Duplicated rather than shared: `:app` and `core:data` do
        // not share test source, and a helper reached across modules would be one more thing to
        // keep true in two places anyway.
        //
        // The call's OWN `runCatching { … }` never appears: it opens and closes on that one line,
        // so it is not a block that is still open there. That is exactly the intent — that wrapper
        // is allowed (a refused move must not cost the notification), it is the one whose refusal
        // `rethrowIfCancelled()` re-throws. Anything ELSE still open is a place for it to die.
        listOf("run" to UNARCHIVE, "onInboxRefreshed" to UNARCHIVE_HOOK).forEach { (function, call) ->
            val body = lines(function)
            val at = body.indexOf(call)
            assertTrue(
                "FetchAndNotify.$function no longer contains the line:\n  $call\nits code is:\n" +
                    body.joinToString("\n"),
                at >= 0,
            )
            val open = openBlocksAt(body, at).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the un-archive call of FetchAndNotify.$function is enclosed in something other " +
                    "than the single `try {` its AccountGoneException arm closes:\n" +
                    open.joinToString("\n") +
                    "\nAccountGoneException IS a CancellationException, so any extra runCatching " +
                    "or try open here swallows the refusal that rethrowIfCancelled() just threw: " +
                    "the arm never runs, the pass carries on to notifyDiff/seed, and the user is " +
                    "notified of new mail for the account she just deleted. Both guards in " +
                    "MailRepository.unarchiveThreadsOnReply become decoration, with every other " +
                    "rule in this file still green.",
                listOf("try {"), open,
            )
        }
    }

    /** The lines that opened the blocks still open just before [index] — the call's enclosure.
     * A twin of `SignedOutAccountWiringTest.openBlocksAt`; see the note above for why it is
     *  copied and not shared. */
    private fun openBlocksAt(lines: List<String>, index: Int): List<String> {
        val stack = ArrayDeque<String>()
        lines.take(index).forEach { line ->
            val delta = line.count { it == '{' } - line.count { it == '}' }
            repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
            repeat(maxOf(0, delta)) { stack.addLast(line) }
        }
        return stack.toList()
    }

    @Test fun `the arm catches the shipped type, not a local look-alike`() {
        assertTrue(
            "FetchAndNotify must import app.sterna.core.data.mail.AccountGoneException — the type " +
                "MailRepository's guard actually throws.",
            "import app.sterna.core.data.mail.AccountGoneException" in lines(),
        )
        assertTrue(
            "FetchAndNotify must import app.sterna.core.data.rethrowIfCancelled — the shipped " +
                "helper that re-throws a cancellation out of a kept Result, not a local copy.",
            "import app.sterna.core.data.rethrowIfCancelled" in lines(),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The [count] lines of [function] starting at the one equal to [first], or a failure. */
    private fun block(function: String, first: String, count: Int): List<String> {
        val body = lines(function)
        val start = body.indexOf(first)
        assertTrue(
            "FetchAndNotify.$function no longer contains the line:\n  $first\nits code is:\n" +
                body.joinToString("\n"),
            start >= 0,
        )
        return body.subList(start, minOf(start + count, body.size))
    }

    /**
     * The code of ONE function of the file, from its declaration to the next declaration's.
     */
    private fun lines(function: String): List<String> {
        val body = lines()
        val bounds = DECLARATIONS.map { declaration ->
            body.indexOfFirst { it.startsWith(declaration) }.also {
                assertTrue(
                    "FetchAndNotify no longer declares '$declaration'; this file slices the source " +
                        "between declarations and cannot tell the two arms apart without them:\n" +
                        body.joinToString("\n"),
                    it >= 0,
                )
            }
        }
        val here = DECLARATIONS.indexOfFirst { it.endsWith(" $function(") }
        assertTrue("no declaration is registered for FetchAndNotify.$function", here >= 0)
        return body.subList(bounds[here], bounds.getOrNull(here + 1) ?: body.size)
    }

    /** The file's code, trimmed, comments and blank lines cut — every rule here is about which
     *  statement follows which, and a comment between two of them changes nothing. */
    private fun lines(): List<String> = FETCH_AND_NOTIFY.readLines().map { it.trim() }.filter {
        it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
    }

    private companion object {
        const val ARM = "} catch (gone: AccountGoneException) {"
        const val PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"
        /** The un-archive call of [FetchAndNotify.run] — bare, its `val returned = if (…)` is above. */
        const val UNARCHIVE =
            "runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }"

        /** The same call in [FetchAndNotify.onInboxRefreshed], where it opens its own statement. */
        const val UNARCHIVE_HOOK = "val returned = $UNARCHIVE"

        /** Every top-level declaration of the file, in source order — the slicing boundaries of
         *  [lines]. A function added between two of these has to be registered here, or its code
         *  is read as part of the one above it. */
        val DECLARATIONS = listOf(
            "suspend fun run(",
            "suspend fun onInboxRefreshed(",
            "internal fun seedsSilently(",
        )

        /** A catch arm, however it is laid out: `} catch (`, `}catch(`, or `catch (` on its own
         *  line after the previous arm's closing brace. */
        val CATCH = Regex("""^}?\s*catch\s*\(""")

        /** Repo root, walked up from the module's working directory. */
        val FETCH_AND_NOTIFY: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $PATH from ${File("").absolutePath} — this test reads the " +
                        "source as text and needs a working directory inside the checkout",
                )
        }
    }
}
