package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and a narrow one: nothing in this chain runs in a JVM test — the receiver needs
 */
class QuickReplyWiringTest {

    @Test fun `mark read still dismisses, and delete only when it moved something`() {
        val body = bodyOf(RECEIVER, "onReceive")
        assertEquals(
            "ONE cancellation on this path, and it is CONDITIONAL. An unconditional dismissal is " +
                "the defect itself: Delete used to take the banner down after a move that moved " +
                "nothing (the message was already in Trash — evicted from the cache and from the " +
                "search index while the server kept it) or after a throw that had already " +
                "unlisted the message from an Empty-trash order and marked it read",
            listOf("if (mayDismiss) dismiss(appContext, accountId, credentials, emailId, notifId)"),
            codeLinesNaming(body, "dismiss("),
        )
        assertEquals(
            "Reply must leave the common path BEFORE that dismissal — put it back in the `when` " +
                "and the notification is taken down whatever happened to the text",
            listOf("if (action == ACTION_REPLY) {"),
            codeLinesNaming(body, "ACTION_REPLY"),
        )
        assertEquals(
            "Mark as read does not change: its work is on the server's copy and is retryable " +
                "from the list, so nothing is lost by dismissing on it",
            listOf("ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)"),
            codeLinesNaming(body, "ACTION_MARK_READ"),
        )
        assertEquals(
            "Delete goes through the decision instead of straight at the repository, and the " +
                "decision is what says whether the banner may fall",
            listOf("ACTION_DELETE -> mayDismiss = deleteFromBanner("),
            codeLinesNaming(body, "deleteFromBanner("),
        )
        assertEquals(
            "…and the flag that carries the answer, EVERY line of it. The pin above names the " +
                "call and is blind by construction to a line added beside it: one `mayDismiss = " +
                "true` after the `when` restores the unconditional dismissal, compiles, and reads " +
                "as deliberate. Three lines, and there is no fourth — it is set once, assigned " +
                "once by the decision, and read once",
            listOf(
                "var mayDismiss = true",
                "ACTION_DELETE -> mayDismiss = deleteFromBanner(",
                "if (mayDismiss) dismiss(appContext, accountId, credentials, emailId, notifId)",
            ),
            codeLinesNaming(body, "mayDismiss"),
        )
        assertEquals(
            "…and EVERY use of the repository in onReceive, enumerated whole: the reply hand-off, " +
                "the mark-read call, and the arguments the banner's delete is handed. A pin that " +
                "names only the calls is blind to a `container.mailRepository.delete(...)` added " +
                "beside them, which is exactly the line this change removes",
            listOf(
                "appContext, container.mailRepository, container.settingsRepository,",
                "ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)",
                "container.mailRepository, credentials, accountId, emailId,",
            ),
            codeLinesNaming(body, "container.mailRepository"),
        )
    }

    /**
     * The end where there is nobody to act for. A signed-out account has no credentials, so none of
     */
    @Test fun `with no credentials nothing runs, so nothing may claim it did`() {
        val block = blockAfter(bodyOf(RECEIVER, "onReceive"), "if (credentials != null)")
        assertEquals(
            "the dismissal must be INSIDE the credentials block, whole. Outside it, a signed-out " +
                "account gets a Delete and a Mark as read that reach no server, take the banner " +
                "down anyway, and leave nothing behind that would do the work: the user is told " +
                "the message was deleted and it never was",
            listOf("if (mayDismiss) dismiss(appContext, accountId, credentials, emailId, notifId)"),
            codeLinesNaming(block, "dismiss("),
        )
        assertEquals(
            "…and it is the block that holds the two server actions, not some other `if` that " +
                "happens to match: an extraction that grabbed the wrong braces would pass the pin " +
                "above while the dismissal sat outside all the same",
            listOf(
                "ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)",
                "ACTION_DELETE -> mayDismiss = deleteFromBanner(",
            ),
            codeLinesNaming(block, "ACTION_"),
        )
    }

    /**
     * The banner's Delete, whose whole job is to ask — locally — the question the screen asks over
     */
    @Test fun `the banner's delete asks the local caches, and only the local caches`() {
        val body = bodyOf(RECEIVER, "deleteFromBanner")
        assertEquals(
            "the message's own folder comes from the CACHE, scoped to the account (#31: an id is " +
                "unique only inside its account)",
            listOf("val cached = runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()"),
            codeLinesNaming(body, "cachedEmail("),
        )
        assertEquals(
            "and its role from the FOLDER cache — `mailboxRole` is a `roleForId` on the local " +
                "table, arguments pinned: the account and the folder of the row just read",
            listOf("val role = runCatching { repo.mailboxRole(accountId, cached?.mailboxId) }.getOrNull()"),
            codeLinesNaming(body, "mailboxRole("),
        )
        assertEquals(
            "and whether the account has a Trash from the same cache, NEVER accountHasTrash — " +
                "and it falls back to `true`, the ordinary path: a failed read must never turn " +
                "into a refusal to delete",
            listOf("val hasTrash = runCatching { repo.hasCachedTrash(accountId) }.getOrDefault(true)"),
            codeLinesNaming(body, "hasCachedTrash("),
        )
        assertEquals(
            "what happens next is DECIDED (BannerDeleteActTest executes it), with the two reads " +
                "as arguments — a literal there is a rule that answers the same thing forever",
            listOf("when (bannerDeleteAct(role, hasTrash)) {"),
            codeLinesNaming(body, "bannerDeleteAct("),
        )
        assertEquals(
            "each arm carries its action ON ITS OWN LINE, so swapping two arms cannot pass as " +
                "the same set of lines. `DoNothing` must reach neither the repository nor the " +
                "dismissal: doing nothing INCLUDES leaving the banner standing",
            listOf(
                "BannerDeleteAct.DoNothing -> return false",
                "BannerDeleteAct.MoveToTrash -> repo.delete(credentials, emailId)",
            ),
            codeLinesNaming(body, "BannerDeleteAct."),
        )
        assertEquals(
            "and BOTH ends of it, whole: what this function returns is the banner's fate, and a " +
                "`return true` turned into `return false` — or a second exit added beside them — " +
                "is a banner that never comes down again on a delete that worked",
            listOf("BannerDeleteAct.DoNothing -> return false", "return true"),
            codeLinesNaming(body, "return"),
        )
    }

    @Test fun `nothing under push resolves a role over the network`() {
        val offenders = File(root, PUSH_SOURCES).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                (codeLinesNaming(source, "deleteWouldDestroy(") + codeLinesNaming(source, "accountHasTrash("))
                    .map { "${file.name}: $it" }
            }
            .toList()
        assertEquals(
            "⛔ both of these resolve the Trash over the SOCKET (roleMailboxId: an IMAP LIST, a " +
                "JMAP connect()). A broadcast receiver has ~10 s of goAsync for everything it " +
                "does, and a reachable-but-silent server spends all of it — the process is then " +
                "killed mid-action. Every question this package asks about a folder is answered " +
                "from the local caches or not asked at all",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the reply notification comes down only once the outbox has the text`() {
        val body = bodyOf(RECEIVER, "reply")
        assertEquals(
            "the recipient and the subject come from the CACHE — no notification's reply may wait " +
                "on a socket to learn where it is going",
            listOf("runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()"),
            codeLinesNaming(body, "cachedEmail("),
        )
        assertEquals(
            "the queueing must be the guarded thing: a throw from enqueueSend (require() on an " +
                "empty recipient, a full disk) has to read as NOT queued",
            listOf("runCatching { send(repo, credentials, emailId, outcome) }.isSuccess"),
            codeLinesNaming(body, "runCatching { send("),
        )
        assertEquals(
            "what happens after the attempt is DECIDED (QuickReplyOutcomeTest executes it), not " +
                "arranged here: the branches are what got swapped by hand and stayed green",
            listOf("when (val act = quickReplyAct(outcome, queued)) {"),
            codeLinesNaming(body, "quickReplyAct("),
        )
        assertEquals(
            "each arm carries its action ON ITS OWN LINE, so swapping two arms cannot pass as the " +
                "same set of lines. This is the shape the lint can hold; the ORDER is held next door",
            listOf(
                "QuickReplyAct.DoNothing -> Unit",
                "QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, emailId, notifId)",
                "is QuickReplyAct.HandBack -> {",
            ),
            codeLinesNaming(body, "QuickReplyAct."),
        )
        assertEquals(
            "the hand-back banner is posted with the setting this path READ — a constant, or the " +
                "old three-argument call under a restored default, is the subject back on the lock " +
                "screen of a user who asked for 'Sender only'",
            listOf("Notifications.notifySendFailed(context, act.subject, content, act.body)"),
            codeLinesNaming(body, "notifySendFailed("),
        )
        assertEquals(
            "and that read is GUARDED. The DataStore has no corruption handler, so `first()` can " +
                "throw; onReceive's catch would swallow it and NO banner would be posted — the " +
                "typed text, which lives in the broadcast intent and nowhere else, would die there",
            listOf("val content = runCatching { settings.notificationContent.first() }"),
            codeLinesNaming(body, "notificationContent"),
        )
        assertEquals(
            "…and it falls back to the QUIETEST position: an unreadable setting must make the " +
                "banner say too little, never too much",
            listOf(".getOrDefault(NotificationContent.NONE)"),
            codeLinesNaming(body, "getOrDefault(NotificationContent."),
        )
        assertEquals(
            "the whole file must cancel in ONE place, the helper that also refreshes the group " +
                "summary (#56/#92) — a second cancel anywhere is the bug walking back in",
            listOf("NotificationManagerCompat.from(context).cancel(notifId)"),
            codeLinesNaming(RECEIVER.readText(), "cancel(notifId)"),
        )
    }

    @Test fun `the fetch is abandoned on the budget, not waited for`() {
        // The leash that was not one: `withTimeoutOrNull { repo.fetchEmail(…) }` only ever regains
        // control at a SUSPENSION point, and fetchEmail blocks its thread all the way down (IMAP
        val body = bodyOf(RECEIVER, "fetchWithinBudget")
        assertEquals(
            "the blocking call must run in a job of its OWN scope — a child would keep the parent " +
                "waiting on the very socket the budget exists to escape",
            listOf("val fetching = CoroutineScope(SupervisorJob() + Dispatchers.IO)"),
            codeLinesNaming(body, "CoroutineScope("),
        )
        assertEquals(
            "and it is the only fetch left on this path",
            listOf(".async { repo.fetchEmail(credentials, emailId) }"),
            codeLinesNaming(body, "fetchEmail("),
        )
        assertEquals(
            "the budget is spent on await() — a real suspension point — never on the blocking " +
                "call itself, or the timeout is decoration",
            listOf(
                "val fetched = runCatching { withTimeoutOrNull(FETCH_BUDGET_MS) { fetching.await() } }.getOrNull()",
            ),
            codeLinesNaming(body, "withTimeoutOrNull"),
        )
        assertEquals(
            "and the abandoned job is cancelled rather than left to run to completion",
            listOf("fetching.cancel()"),
            codeLinesNaming(body, "fetching.cancel("),
        )
        assertEquals(
            "the leash is a named constant, and it is short — threading headers are never worth " +
                "the text",
            listOf("private const val FETCH_BUDGET_MS = 5_000L"),
            codeLinesNaming(RECEIVER.readText(), "FETCH_BUDGET_MS ="),
        )
    }

    @Test fun `the queued message carries the typed text, and no dead shortcut guards it`() {
        val body = bodyOf(RECEIVER, "send")
        assertEquals(
            "the send asks for the threading headers through the bounded helper, and sends " +
                "whatever it got — including nothing",
            listOf("val fetched = fetchWithinBudget(repo, credentials, emailId)"),
            codeLinesNaming(body, "fetchWithinBudget("),
        )
        assertEquals(
            "the recipient, subject and body handed to the Outbox are the decided ones",
            listOf("to = listOf(outcome.to),", "subject = outcome.subject,", "body = outcome.body,"),
            codeLinesNaming(body, "outcome."),
        )
        assertEquals(
            "⛔ the `cached row already has a Message-Id` shortcut described a path that CANNOT " +
                "run: EmailEntity has no messageId/references column and EmailMapper.toEmail never " +
                "fills them, so cachedIds was always empty. Dead code that documents a lie",
            emptyList<String>(),
            codeLinesNaming(RECEIVER.readText(), "cachedIds"),
        )
    }

    @Test fun `the recipient is chosen by the composer's own function, not a second copy`() {
        assertEquals(
            "the choice of who a reply answers must exist ONCE (ComposeText.replyRecipient), or " +
                "the next change to it — Reply-To — has to be made twice and will be made once",
            listOf("val to = cached?.let { replyRecipient(it) }.orEmpty().trim()"),
            codeLinesNaming(QUICK_REPLY.readText(), "replyRecipient("),
        )
    }

    @Test fun `the handed-back text travels as a body-only mailto`() {
        val source = NOTIFICATIONS.readText()
        assertEquals(
            "the salvaged text must reach the composer through the existing mailto: route — no " +
                "new screen, no new string. NO `to=` beside the body: MailTo.parse hands back an " +
                "address with an EMPTY body when both are given, and here there is no address",
            listOf(
                """Uri.parse("mailto:?subject=${'$'}{Uri.encode(subject)}&body=${'$'}{Uri.encode(body)}")""",
            ),
            codeLinesNaming(source, "mailto:"),
        )
        assertEquals(
            "the salvaged text stays optional (a scheduled send has none), but [content] must NOT " +
                "get a default: a default is how a future caller leaks the subject in silence, and " +
                "it is exactly how the snooze wake-up leaked sender and subject in #84",
            listOf(
                """fun notifySendFailed(context: Context, subject: String, content: NotificationContent, body: String = "") {""",
            ),
            codeLinesNaming(source, "fun notifySendFailed"),
        )
        assertEquals(
            "the CALL SITE's arguments are pinned too, not just the shape of the URI: " +
                "draftUri(subject, \"\") compiles, opens an empty composer, and reads as fixed",
            listOf(
                "Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)",
                "private fun draftUri(subject: String, body: String): Uri =",
            ),
            codeLinesNaming(source, "draftUri("),
        )
        assertEquals(
            "a notification holding salvaged text must NOT auto-cancel: the mailto: is consumed by " +
                "the NavHost, which is only composed when signed in, so the hand-back raised for a " +
                "signed-out account opens nothing — and an auto-cancelled tap would then be the " +
                "second and final loss of the text. Unchanged for a body-less failure",
            listOf(".setAutoCancel(body.isBlank())"),
            // Scoped to this one builder: new-mail and snooze notifications auto-cancel on tap
            // and must keep doing so.
            codeLinesNaming(bodyOf(NOTIFICATIONS, "notifySendFailed"), "setAutoCancel("),
        )
        assertEquals(
            "and the notification is keyed on the body too: two salvaged texts under one key " +
                "replace each other under FLAG_UPDATE_CURRENT, which loses the first",
            listOf("""val key = if (body.isBlank()) "sendfail:${'$'}subject" else "sendfail:${'$'}subject:${'$'}body""""),
            codeLinesNaming(source, "val key ="),
        )
    }

    /**
     * The banner of a failed send is the one notification that never asked what it was allowed to
     */
    @Test fun `the failure banner shows the decided line, never the raw subject`() {
        val body = bodyOf(NOTIFICATIONS, "notifySendFailed")
        assertEquals(
            "the stand-in is resolved here and handed IN: MailNotificationText holds no resources",
            listOf("val noSubject = context.getString(R.string.message_no_subject)"),
            codeLinesNaming(body, "message_no_subject"),
        )
        assertEquals(
            "and the line is DECIDED, with the read setting and the real subject as arguments — " +
                "pinning the call without its arguments would let `content` be swapped for a constant",
            listOf("val line = MailNotificationText.sendFailureLine(content, subject, noSubject)"),
            codeLinesNaming(body, "sendFailureLine("),
        )
        assertEquals(
            "the collapsed line is the decided one. `subject` here is the leak itself",
            listOf("setContentText(line)"),
            codeLinesNaming(body, "setContentText("),
        )
        assertEquals(
            "so is the expanded one — the lock screen reads THIS, and the first fix of this kind " +
                "left bigText behind",
            listOf("setStyle(NotificationCompat.BigTextStyle().bigText(line))"),
            codeLinesNaming(body, "bigText("),
        )
        assertEquals(
            "and when the decision says nothing may be shown, NEITHER line is set at all: the " +
                "title alone already says the message was not sent",
            listOf("if (line != null) {"),
            codeLinesNaming(body, "if (line != null)"),
        )
        assertEquals(
            "the title never varies — no new string was added for this, and none is needed",
            listOf(".setContentTitle(context.getString(R.string.notif_send_failed_title))"),
            codeLinesNaming(body, "setContentTitle("),
        )
        assertEquals(
            "⛔ AND THIS IS THE WHOLE OF IT: every line of the builder that names `subject`, " +
                "enumerated. The pins above hold the lines they name, and are blind by " +
                "construction to a line ADDED beside them — `.setSubText(subject)` or " +
                "`.setTicker(subject)` puts the subject back in the notification header, " +
                "collapsed, expanded and on the lock screen, and would pass every other assertion " +
                "in this file. The four lines below are the only ones allowed to touch it, and " +
                "not one of them reaches the screen: an id, a mailto: inside a PendingIntent (which " +
                "shows nobody its Intent), a resource name, and the argument handed to the decision",
            listOf(
                """val key = if (body.isBlank()) "sendfail:${'$'}subject" else "sendfail:${'$'}subject:${'$'}body"""",
                "Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)",
                "val noSubject = context.getString(R.string.message_no_subject)",
                "val line = MailNotificationText.sendFailureLine(content, subject, noSubject)",
            ),
            codeLinesNaming(body, "subject"),
        )
    }

    /**
     * Both callers, and the same failure for both: a scheduled send that gave up leaks the subject
     */
    @Test fun `the scheduled send reads the setting too, and passes what it read`() {
        val body = bodyOf(SCHEDULED, "doWork")
        assertEquals(
            "read from the settings repository, like every other notification path " +
                "(NewMailNotifier.options) — never a constant, never a default. GUARDED, and for " +
                "a reason of its own: this read sits INSIDE the catch, so a throw would escape " +
                "doWork() and skip both the banner and the deleteScheduledSend under it, leaving " +
                "the overdue row the comment above exists to clear",
            listOf("val content = runCatching { container.settingsRepository.notificationContent.first() }"),
            codeLinesNaming(body, "notificationContent"),
        )
        assertEquals(
            "…and to the same quietest position as the quick-reply path: the two must not drift",
            listOf(".getOrDefault(NotificationContent.NONE)"),
            codeLinesNaming(body, "getOrDefault(NotificationContent."),
        )
        assertEquals(
            "and handed to the banner as it was read",
            listOf("Notifications.notifySendFailed(applicationContext, row.subject, content)"),
            codeLinesNaming(body, "notifySendFailed("),
        )
        assertEquals(
            "these two are the only callers in the whole app, BY FILE and not by count: a third " +
                "written with a direct import (`import …Notifications.notifySendFailed`) names no " +
                "`Notifications.` at all, and a count would never have seen it",
            listOf("NotificationActionReceiver.kt", "Notifications.kt", "ScheduledSendWorker.kt"),
            File(root, "app/src/main/kotlin").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { codeLinesNaming(it.readText(), "notifySendFailed").isNotEmpty() }
                .map { it.name }.toList().sorted(),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The braced block opened right after [header] in [body] — braces included.
     */
    private fun blockAfter(body: String, header: String): String {
        val start = body.indexOf(header)
        require(start >= 0) {
            "no `$header` left in this function — removed, or WIDENED into something else? The " +
                "closing paren is part of the needle on purpose: `if (credentials != null && …)` " +
                "is a different guard and must not pass as this one"
        }
        val open = body.indexOf('{', start + header.length)
        require(open >= 0) { "`$header` opens no block" }
        var depth = 0
        var i = open
        while (i < body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return body.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces after `$header`")
    }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    companion object {
        private const val PUSH_SOURCES = "app/src/main/kotlin/app/sterna/push"
        private const val RECEIVER_PATH = "$PUSH_SOURCES/NotificationActionReceiver.kt"
        private const val QUICK_REPLY_PATH = "$PUSH_SOURCES/QuickReply.kt"
        private const val NOTIFICATIONS_PATH = "$PUSH_SOURCES/Notifications.kt"
        private const val SCHEDULED_PATH = "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, RECEIVER_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val RECEIVER: File by lazy { File(root, RECEIVER_PATH) }
        private val QUICK_REPLY: File by lazy { File(root, QUICK_REPLY_PATH) }
        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val SCHEDULED: File by lazy { File(root, SCHEDULED_PATH) }
    }
}
