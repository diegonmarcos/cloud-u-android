package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class RelayWiringTest {

    // -- the promise of the branch: a relay account never speaks JMAP -----------------------------

    @Test fun `only a JMAP registration asks the mail server for a VAPID key`() {
        val body = managerBlock(ENSURE_REGISTERED)
        assertEquals(
            "the VAPID lookup must be written as '$EXPECTED_VAPID' — the whole line. pushVapidKey " +
                "is a JMAP session call: without the 'if (relay)' in front of it, an IMAP account " +
                "that asked for a relay address sends its credentials to a host that speaks IMAP, " +
                "on every single registration. This is what the branch is FOR.",
            listOf(EXPECTED_VAPID),
            body.filter { "pushVapidKey" in it },
        )
        assertEquals(
            "'relay' must be decided as '$EXPECTED_RELAY_FLAG' — the whole line. Inverting this " +
                "comparison inverts the guard above and nothing else in the file would say so.",
            listOf(EXPECTED_RELAY_FLAG),
            body.filter { it.startsWith("val relay ") },
        )
        assertEquals(
            "pushVapidKey may be named on exactly ONE line of the whole file: a second call site " +
                "is a JMAP request on a path this rule has never read.",
            1,
            managerLines().count { "pushVapidKey" in it },
        )
    }

    // -- the transport decision: only a REAL delivery moves an IMAP account off IDLE ----------

    @Test fun `the UnifiedPush arm of transportFor is the isActive line, whole`() {
        assertEquals(
            "the UnifiedPush arm of PushController.transportFor must be exactly " +
                "'$EXPECTED_UNIFIED_PUSH_ARM' — the whole line. This is THE line of the branch: " +
                "isActive is true for a relay only once a payload really arrived " +
                "([RelayPush.onDelivery]), and widening it by so much as an '|| " +
                "up.relayEndpoint(id) != null' hands a merely PUBLISHED account — an address " +
                "exists, nobody has ever posted to it — from IMAP IDLE to a dead endpoint, " +
                "foreground service and all. Nothing executes transportFor (it needs " +
                "Application.container; see PushWatchTest's KNOWN GAP), so this text is the only " +
                "guard it has. Lines found:\n" +
                transportForArms().filter { "UNIFIED_PUSH" in it }.joinToString("\n"),
            listOf(EXPECTED_UNIFIED_PUSH_ARM),
            transportForArms().filter { "UNIFIED_PUSH" in it },
        )
    }

    /** The negative half — `contains`, deliberately: it screens for what must be ABSENT. */
    @Test fun `transportFor reads no address and no published state`() {
        val offenders = transportForBody().filter { line ->
            FORBIDDEN_IN_TRANSPORT.any { it in line }
        }
        assertEquals(
            "nothing inside transportFor may name any of " +
                FORBIDDEN_IN_TRANSPORT.joinToString(", ") + ". An address, a PUBLISHED state or " +
                "the mere fact that a relay was requested are all 'somebody might post here one " +
                "day'; the transport may only move on what already happened. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the arms of transportFor are pinned whole and in order`() {
        assertEquals(
            "transportFor decides in one 'when', and its arms are pinned whole and in order. " +
                "isLinked first: a shared sub-account has no push of its own (#31) and would " +
                "otherwise inherit its login's UnifiedPush. batterySaver AFTER the UnifiedPush " +
                "arm and not before it: UnifiedPush costs Sterna nothing, so battery saver must " +
                "not knock an armed account down to a 30-minute poll. Reorder these and no other " +
                "test in the repo says a word.",
            EXPECTED_TRANSPORT_ARMS,
            transportForArms(),
        )
    }

    // -- the monitor: every state write that is not inside a coroutine is under it ----------------

    @Test fun `ensureRegistered still carries its monitor`() {
        val lines = managerLines()
        val at = lines.indexOfFirst { it == ENSURE_REGISTERED }
        check(at > 0) { "no '$ENSURE_REGISTERED' in UnifiedPushManager.kt" }
        assertEquals(
            "ensureRegistered must be annotated @Synchronized, on the line directly above its " +
                "signature. It IS the monitor every other rule in this file refers to: apply() " +
                "calls it from several places in quick succession (account switch, transport " +
                "callbacks), and without the annotation the load/save race registers the same " +
                "account many times over. Every 'synchronized(this)' below is synchronized with " +
                "THIS — take the annotation off and they all still compile, still lock, and " +
                "protect nothing from the caller they exist for.",
            "@Synchronized",
            lines[at - 1],
        )
    }

    @Test fun `no state write happens outside the monitor`() {
        val offenders = MONITORED.associateWith { signature ->
            outsideTheMonitor(managerBlock(signature)).filter { "store.save(" in it }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "these functions read, decide and write the transport state from threads that are " +
                "not the caller's — the connector's binder thread (onMessage → recordDelivery, " +
                "onUnregistered), a failed coroutine (markFailed), the controller " +
                "(reconcileDistributorPresence) — while the settings screen and the endpoint " +
                "coroutine write under the monitor. Their whole read-decide-write must sit under " +
                "'synchronized(this)' too. recordDelivery is the one that hurts: it is the ONLY " +
                "write that arms the transport, and a rotation persisting {new address, " +
                "PUBLISHED, never woken} under the lock, interleaved with a delivery that read " +
                "'prev' before it, ends as {OLD address, ACTIVE, woken just now} — IMAP IDLE " +
                "dropped, a dead address on screen, and 'last wake: just now' under it. ⛔ The " +
                "lock covers state ONLY: no network call, no UnifiedPush.register/unregister and " +
                "no scope.launch may sit inside it, which is why onNewEndpoint's Subscribe path " +
                "(a write AFTER a server call) is deliberately not in this list. Writes found " +
                "outside the monitor:\n" + offenders.entries.joinToString("\n"),
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    // -- the registration gates, in order ---------------------------------------------------------

    @Test fun `the gates of ensureRegistered are in this order`() {
        val expected = listOf(
            "if (accountStore.account(credentials.id)?.isLinked == true) return",
            "val known = store.load(credentials.id)",
            "if (!RelayPush.mayUsePush(credentials.protocol, known?.relayRequested ?: false)) return",
            "if (!ensureDistributor()) return",
            "if (!RelayPush.shouldRegister(",
        )
        assertEquals(
            "ensureRegistered must open with exactly these lines, in this order. Two rules meet " +
                "here and both are load-bearing: an account that may not use push AT ALL leaves " +
                "before ensureDistributor(), whose picker is the one piece of UI in this subsystem " +
                "(an IMAP account nobody asked a relay for must never raise it, and must leave no " +
                "prefs record either — hence store.load, not getOrCreate); and a JMAP account goes " +
                "THROUGH ensureDistributor() whatever the registration window then says, because " +
                "that call is also what silently enrols a single installed distributor. Put " +
                "shouldRegister ahead of it and a JMAP account marked FAILED the moment its " +
                "distributor was uninstalled cannot see the replacement for fifteen minutes.",
            expected,
            managerBlock(ENSURE_REGISTERED).take(expected.size),
        )
    }

    // -- the endpoint path stops at the relay ------------------------------------------------------

    @Test fun `an arriving endpoint is read, decided and written under the registration lock`() {
        val expected = listOf(
            "val (prev, outcome) = synchronized(this@UnifiedPushManager) {",
            "val current = store.getOrCreate(accountId)",
            "val decided = RelayPush.endpointOutcome(",
            "credentials.protocol, current, endpoint.url, System.currentTimeMillis(),",
            ")",
            "if (decided is EndpointOutcome.Relay) store.save(accountId, decided.state)",
            "current to decided",
            "}",
        )
        val body = managerBlock(ON_NEW_ENDPOINT)
        val at = body.indexOfFirst { it.startsWith("val (prev, outcome)") }
        assertEquals(
            "the Relay branch must read, decide and write under the SAME monitor as " +
                "ensureRegistered, in one block, compared whole. This coroutine runs on " +
                "Dispatchers.IO while the settings screen can be writing: read the state outside " +
                "the lock and a withdrawal that lands in between is overwritten by a state built " +
                "on a 'prev' from before it — the address the user just removed comes back, " +
                "published and marked as requested.",
            expected,
            if (at < 0) body.take(expected.size) else body.subList(at, minOf(at + expected.size, body.size)),
        )
    }

    @Test fun `the relay branch persists its own state and stops there`() {
        val expected = listOf(
            "is EndpointOutcome.Relay -> {",
            "Log.i(TAG, \"Relay address published for \$accountId\")",
            "onTransportStateChanged?.invoke()",
            "return@launch",
            "}",
        )
        val body = managerBlock(ON_NEW_ENDPOINT)
        val at = body.indexOfFirst { it == expected.first() }
        assertEquals(
            "the Relay branch of onNewEndpoint is pinned WHOLE, line by line and in order. The " +
                "'return@launch' is the load-bearing one: without it an IMAP account falls " +
                "straight through into destroyPushSubscription/createPushSubscription against a " +
                "host that speaks IMAP. Nothing in this module executes these lines.",
            expected,
            if (at < 0) body.take(expected.size) else body.subList(at, minOf(at + expected.size, body.size)),
        )
    }

    // -- an address anyone may post to is not an authenticated channel ------------------------------

    @Test fun `a verification payload is answered only by an account that speaks JMAP`() {
        val expected = listOf(
            "val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)",
            "if (credentials.protocol != MailProtocol.JMAP) {",
            "Log.w(TAG, \"Push verification on a relay account — ignored: \$accountId\")",
            "return",
            "}",
        )
        assertEquals(
            "the Verification arm must open with exactly these lines, in this order. The relay " +
                "address is a topic ANYONE may write to: without this guard, a stranger who knows " +
                "the address posts {\"@type\":\"PushVerification\"} and Sterna hands the account's " +
                "credentials to jmapAuth against an IMAP host — the failure then calls markFailed, " +
                "so that stranger can switch the relay off for fifteen minutes, silently, as often " +
                "as they like; and on an OAuth account it burns a real token refresh first. ⛔ The " +
                "question is the PROTOCOL, not the stored subscriptionId: on a first subscription " +
                "that id is written only after createPushSubscription returns, so a server that " +
                "verifies promptly (Stalwart times the round trip out in about a minute) could be " +
                "answered by nobody and the account would sit in VERIFYING until it went stale. " +
                "The guard is here to keep a RELAY account out, and a relay account has no " +
                "protocol race to lose. The log line must not carry the payload.",
            expected,
            managerBlock(VERIFICATION_ARM).take(expected.size),
        )
    }

    @Test fun `nothing in the verification arm records a delivery`() {
        val offenders = managerBlock(VERIFICATION_ARM).filter { "recordDelivery" in it }
        assertEquals(
            "the Verification arm must never call recordDelivery: a JMAP account's ACTIVE is its " +
                "server's PushVerification, and a payload that merely arrived must not counterfeit " +
                "it. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- only the two arms that carry mail stamp a delivery ------------------------------------------

    @Test fun `only the two arms that carry mail record a delivery`() {
        val body = managerBlock(ON_MESSAGE)
        assertEquals(
            "onMessage must call recordDelivery on exactly two lines, and both written as " +
                "'recordDelivery(accountId)'. It is the ONLY thing that arms a relay: a third " +
                "call site arms it on something that is not a delivery, and a missing one leaves " +
                "an account that really is being woken reading 'checked every 30 minutes' for " +
                "ever. Lines found:\n" + body.filter { "recordDelivery" in it }.joinToString("\n"),
            listOf("recordDelivery(accountId)", "recordDelivery(accountId)"),
            body.filter { "recordDelivery" in it },
        )
    }

    @Test fun `the change arm and the unparsed arm are pinned whole`() {
        val change = listOf(
            "is PushMessagePayload.Change -> {",
            "recordDelivery(accountId)",
            "enqueueForLogin(accountId)",
            "}",
        )
        val unparsed = listOf(
            "null -> {",
            "Log.i(TAG, \"Unparsed push payload (\${message.content.size}B, decrypted=\${message.decrypted})\")",
            "recordDelivery(accountId)",
            "enqueueForLogin(accountId)",
            "}",
        )
        val body = managerBlock(ON_MESSAGE)
        assertEquals(
            "the mail-carrying arm of onMessage is pinned whole: the delivery is stamped BEFORE " +
                "the fetch is enqueued, and nothing else happens in it.",
            change,
            slice(body, change),
        )
        assertEquals(
            "the unparsed arm is pinned whole too: an undecryptable payload is still a wake, and " +
                "its log line carries the SIZE and the decrypted flag only — never the payload.",
            unparsed,
            slice(body, unparsed),
        )
    }

    // -- the two settings gates ----------------------------------------------------------------------

    @Test fun `asking for an address in person starts from zero, under the lock`() {
        val expected = listOf(
            "synchronized(this) {",
            "val state = store.getOrCreate(credentials.id)",
            "store.save(credentials.id, RelayPush.onRelayRequested(state, System.currentTimeMillis()))",
            "}",
            "ensureRegistered(credentials)",
        )
        assertEquals(
            "requestRelayAddress must be exactly these lines. RelayPush.onRelayRequested is what " +
                "clears the status: withdrawing an address unregisters the instance, the " +
                "distributor answers, onUnregistered writes FAILED stamped now — and tapping " +
                "'Get an address' a second later then did NOTHING for fifteen minutes without a " +
                "word on screen. And the read/write sits under the same monitor as " +
                "ensureRegistered, or it races the endpoint delivery on Dispatchers.IO.",
            expected,
            managerBlock(REQUEST_RELAY),
        )
    }

    @Test fun `withdrawing an address refuses to touch an account that holds a subscription`() {
        val expected = listOf(
            "synchronized(this) {",
            "val state = store.load(credentials.id) ?: return",
            "if (state.subscriptionId != null) {",
            "Log.w(TAG, \"dropRelayAddress on an account holding a subscription — ignored: \${credentials.id}\")",
            "return",
            "}",
        )
        val body = managerBlock(DROP_RELAY)
        assertEquals(
            "dropRelayAddress must open with exactly these lines. It is teardown's twin without " +
                "the server call, which is only correct for an account that never held a " +
                "PushSubscription: called on one that does, it erases the id locally and leaves " +
                "the subscription alive on the server, posting to an endpoint that no longer " +
                "exists, and the next registration creates a second one. It is a public function; " +
                "'the screen only calls it for IMAP' is not a guard.",
            expected,
            body.take(expected.size),
        )
        assertEquals(
            "and the unregister must be the LAST thing it does, outside the lock: the connector " +
                "call is not state, and holding the monitor across it blocks ensureRegistered.",
            listOf(
                "UnifiedPush.unregister(context, credentials.id)",
                "onTransportStateChanged?.invoke()",
            ),
            body.takeLast(2),
        )
    }

    @Test fun `the address handed to the screen is a live one or none`() {
        assertEquals(
            "relayEndpoint must be written as '$EXPECTED_RELAY_ENDPOINT' — the whole line. " +
                "Returning the stored field whatever the status shows a DEAD address as a live " +
                "one: markFailed and onUnregistered both keep it, and the user copies it into a " +
                "relay that then posts into a void for ever. RelayPush.publishedAddress is the " +
                "executed rule ([RelayPushTest]); this line is the wiring to it.",
            listOf(EXPECTED_RELAY_ENDPOINT),
            managerLines().filter { it.startsWith("fun relayEndpoint") },
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /** The first slice of [body] as long as [expected], anchored on [expected]'s first line. */
    private fun slice(body: List<String>, expected: List<String>): List<String> {
        val at = body.indexOfFirst { it == expected.first() }
        if (at < 0) return body.take(expected.size)
        return body.subList(at, minOf(at + expected.size, body.size))
    }

    /** The body of the block opened by [opener] in UnifiedPushManager.kt. */
    private fun managerBlock(opener: String): List<String> =
        blockOf(managerLines(), opener, "UnifiedPushManager.kt")

    /** The whole body of `PushController.transportFor`, the private overload that decides. */
    private fun transportForBody(): List<String> =
        blockOf(codeLines(CONTROLLER), TRANSPORT_FOR, "PushController.kt")

    /**
     * The arms of `transportFor`'s `when`, in source order.
     */
    private fun transportForArms(): List<String> =
        blockOf(transportForBody(), "return when {", "PushController.transportFor")

    /**
     * The lines of [body] that are NOT nested inside a `synchronized(this…) {` block.
     */
    private fun outsideTheMonitor(body: List<String>): List<String> {
        var depth = 0
        var monitorAt: Int? = null
        val out = mutableListOf<String>()
        for (line in body) {
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            monitorAt?.let { if (depth - closes <= it) monitorAt = null }
            if (monitorAt == null) {
                if ("synchronized(this" in line && line.endsWith("{")) monitorAt = depth else out += line
            }
            depth += opens - closes
        }
        return out
    }

    /** The body of the block opened by [opener], closed by counting braces. */
    private fun blockOf(lines: List<String>, opener: String, where: String): List<String> {
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in $where — it was renamed or reshaped, and this lint must be taught " +
                "the new shape rather than left green over code it never read"
        }
        var depth = lines[at].count { it == '{' } - lines[at].count { it == '}' }
        val out = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return out
            out += line
        }
        error("'$opener' is never closed in $where")
    }

    /** Trimmed lines, comment-only ones dropped so no rule is satisfied by prose. */
    private fun managerLines(): List<String> = codeLines(MANAGER)

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        const val ENSURE_REGISTERED = "fun ensureRegistered(credentials: AccountCredentials) {"
        const val ON_NEW_ENDPOINT = "fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {"
        const val ON_MESSAGE = "fun onMessage(accountId: String, message: PushMessage) {"
        const val VERIFICATION_ARM = "is PushMessagePayload.Verification -> {"
        const val REQUEST_RELAY = "fun requestRelayAddress(credentials: AccountCredentials) {"
        const val DROP_RELAY = "fun dropRelayAddress(credentials: AccountCredentials) {"
        const val TRANSPORT_FOR =
            "private fun transportFor(context: Context, credentials: AccountCredentials, " +
                "batterySaver: Boolean): Transport {"

        /** The four state writers that run on a thread of their own. */
        val MONITORED = listOf(
            "private fun recordDelivery(accountId: String) {",
            "private fun markFailed(accountId: String) {",
            "fun onUnregistered(accountId: String) {",
            "fun reconcileDistributorPresence() {",
        )

        const val EXPECTED_UNIFIED_PUSH_ARM = "up.isActive(credentials.id) -> Transport.UNIFIED_PUSH"

        val EXPECTED_TRANSPORT_ARMS = listOf(
            "container.accountStore.account(credentials.id)?.isLinked == true -> Transport.PERIODIC",
            EXPECTED_UNIFIED_PUSH_ARM,
            "batterySaver -> Transport.PERIODIC",
            "credentials.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE",
            "else -> Transport.IMAP_IDLE",
        )

        val FORBIDDEN_IN_TRANSPORT =
            listOf("relayEndpoint", "PUBLISHED", "relayRequested", "publishedAddress")

        const val EXPECTED_VAPID =
            "val vapid = if (relay) null else runCatching { repo.pushVapidKey(credentials) }.getOrNull()"
        const val EXPECTED_RELAY_FLAG = "val relay = credentials.protocol != MailProtocol.JMAP"
        const val EXPECTED_RELAY_ENDPOINT =
            "fun relayEndpoint(accountId: String): String? = RelayPush.publishedAddress(store.load(accountId))"

        const val MANAGER_PATH = "app/src/main/kotlin/app/sterna/push/UnifiedPushManager.kt"
        const val CONTROLLER_PATH = "app/src/main/kotlin/app/sterna/push/PushController.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MANAGER_PATH).isFile }
                ?: error(
                    "cannot locate $MANAGER_PATH from ${File("").absolutePath} — this lint reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val MANAGER: File by lazy { File(root, MANAGER_PATH) }
        val CONTROLLER: File by lazy { File(root, CONTROLLER_PATH) }
    }
}
