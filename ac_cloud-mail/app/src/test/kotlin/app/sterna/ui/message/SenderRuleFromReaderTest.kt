package app.sterna.ui.message

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The per-sender filter rule offered from the message being read (the ⋮ of the SENDER's row in
 */
class SenderRuleFromReaderTest {

    private val address = "news@example.com"

    /** The account doing the reading — an identity and an alias, as a two-alias account has. */
    private val own = listOf("alex.rivera@masto.top", "alex@alias.example")

    private fun ruled(vararg addresses: String) = FilterRulesState.Loaded(
        addresses.map {
            FilterRule(name = it, field = RuleField.FROM, match = RuleMatch.IS, value = it)
        },
    )

    private fun read(state: FilterRulesState) = SenderScript.Read(state)

    /**
     * The decision, with everything that is not the subject of a given test held at its ordinary
     */
    private fun entry(
        isSender: Boolean = true,
        trashPath: String? = "Trash",
        script: SenderScript = read(ruled()),
        address: String = this.address,
        ownAddresses: List<String> = own,
    ) = senderRuleEntry(isSender, trashPath, script, address, ownAddresses)

    @Test fun `the sender of the open message gets the entry`() {
        assertEquals(SenderRuleEntry.OFFERED, entry())
    }

    @Test fun `a recipient does not`() {
        // A rule on FROM aimed at someone who was only in To or Cc is a rule about mail that
        // person has not sent. The panel lists all three groups with the same row, so the group
        // is an argument of the decision and not "whichever group happened to get a callback".
        assertEquals(SenderRuleEntry.ABSENT, entry(isSender = false))
        assertEquals(
            "and no state of the account brings it back on a recipient's row",
            listOf(SenderRuleEntry.ABSENT, SenderRuleEntry.ABSENT, SenderRuleEntry.ABSENT),
            listOf(ruled(), ruled(address), FilterRulesState.Unsupported).map {
                entry(isSender = false, script = read(it))
            },
        )
    }

    // -- the two noes about the ADDRESS, asked before anything the account's script says ---------

    @Test fun `the reader is never offered a rule against itself`() {
        // R6, seen on a device (banc-1.4.8.md § 4): a message opened from the Sent folder carries
        // the account's own identity in From, the entry was ACTIVE on it, and the dialog it
        assertEquals(SenderRuleEntry.ABSENT, entry(address = "alex.rivera@masto.top"))
        assertEquals(
            "an alias of the account is just as much oneself",
            SenderRuleEntry.ABSENT,
            entry(address = "alex@alias.example"),
        )
        assertEquals(
            "…and case is no way around it: the rule matches the address, not its spelling",
            SenderRuleEntry.ABSENT,
            entry(address = "Alex.Rivera@Masto.TOP"),
        )
    }

    @Test fun `no state of the account's script brings the entry back on one's own address`() {
        // The refusal is asked BEFORE anything the server said, so nothing the server says can
        // undo it — not a script nobody read, not an unreachable one, not another script running.
        // If any of these ever reads OFFERED, the gesture is on offer against oneself again.
        val states = listOf(
            SenderScript.Unread,
            SenderScript.Unreachable,
            read(ruled()),
            read(ruled("alex.rivera@masto.top")),
            read(FilterRulesState.Loaded(emptyList(), foreignActiveScript = true)),
            read(FilterRulesState.Unsupported),
        )
        assertEquals(
            states.map { SenderRuleEntry.ABSENT },
            states.map { entry(script = it, address = "alex.rivera@masto.top") },
        )
    }

    @Test fun `a From with no address at all has nothing to rule on`() {
        // A From: carrying only a display name maps to email = "" (EmailMapper). `FROM :is ""`
        // is a rule about nobody: no mail matches it, nothing reads it back, and it stays in the
        // account's script.
        assertEquals(SenderRuleEntry.ABSENT, entry(address = ""))
        assertEquals(SenderRuleEntry.ABSENT, entry(address = "   "))
    }

    // -- the noes that ARE about the account ----------------------------------------------------

    @Test fun `no Trash to name, and no server support, take the entry away`() {
        // The screen's own two silences, kept: with nothing to file the mail into there is
        // nothing honest to offer, and on an account whose server has no Sieve the rule would be
        // refused. Neither can be explained here any better than it is on the Filters screen.
        assertEquals(SenderRuleEntry.ABSENT, entry(trashPath = null))
        assertEquals(SenderRuleEntry.ABSENT, entry(script = read(FilterRulesState.Unsupported)))
    }

    @Test fun `an address the script already handles keeps its entry, greyed, and says why`() {
        val entry = entry(script = read(ruled(address)))
        assertEquals(SenderRuleEntry.ALREADY_RULED, entry)
        assertEquals(app.sterna.R.string.sender_volume_block_done, senderRuleLabel(entry))
    }

    @Test fun `the address is matched the way the rule matches it`() {
        // alreadyBlocked compares FROM/IS rules case-insensitively, which is how the per-sender
        // screen groups. A second identical rule would file the same mail twice.
        assertEquals(SenderRuleEntry.ALREADY_RULED, entry(script = read(ruled("News@Example.com"))))
        assertEquals(
            "a rule about somebody else is not this address's rule",
            SenderRuleEntry.OFFERED,
            entry(script = read(ruled("other@example.com"))),
        )
    }

    @Test fun `another Sieve script greys the entry instead of hiding it`() {
        // The one place this location beats the list row: the reason can be SAID. On the
        // per-sender screen the entry disappears with no word anywhere; here it stays, disabled,
        // wearing the reason.
        val entry = entry(
            script = read(FilterRulesState.Loaded(emptyList(), foreignActiveScript = true)),
        )
        assertEquals(SenderRuleEntry.FOREIGN_SCRIPT, entry)
        assertEquals(app.sterna.R.string.sender_volume_block_foreign, senderRuleLabel(entry))
    }

    @Test fun `already handled answers before another script is blamed`() {
        // Both true at once. Neither branch writes anything, so they differ only in what the
        // reader is told, and "another script is active" on an address that is already handled
        // reports an obstacle where there is none. Same order as addBlockRule's, and for the
        // same reason (that one went red with "expected:<ALREADY_PRESENT> but was:<FAILED>").
        assertEquals(
            SenderRuleEntry.ALREADY_RULED,
            entry(
                script = read(
                    FilterRulesState.Loaded(ruled(address).rules, foreignActiveScript = true),
                ),
            ),
        )
    }

    // -- "not asked yet" and "asked, no answer" are two different states -------------------------

    @Test fun `a script nobody has read yet holds the entry back`() {
        // The other half of R6: "not read" and "the read failed" were ONE null state, and the
        // entry was offered in both. Before the panel's read answers, nothing is known — whether
        assertEquals(SenderRuleEntry.ABSENT, entry(script = SenderScript.Unread))
    }

    @Test fun `a read that got no answer leaves the entry offered`() {
        // And this is the state that must NOT be hidden — which is the whole reason the two are
        // told apart. Hiding the entry offline makes an unreachable server look like an IMAP
        assertEquals(SenderRuleEntry.OFFERED, entry(script = SenderScript.Unreachable))
        assertEquals(
            "…but a Trash that cannot be named is still nothing to offer",
            SenderRuleEntry.ABSENT,
            entry(script = SenderScript.Unreachable, trashPath = null),
        )
    }

    @Test fun `the offered entry wears the gesture's own words, which are not the unsubscribe's`() {
        // The distinction the two menus are two taps apart on the same message: "Unsubscribe"
        // ASKS the sender to stop and depends on their goodwill; this one asks nobody — the
        // server applies it, to anyone. They must not be able to read as the same thing.
        assertEquals(app.sterna.R.string.sender_volume_block, senderRuleLabel(SenderRuleEntry.OFFERED))
        assertEquals(app.sterna.R.string.sender_volume_block, senderRuleLabel(SenderRuleEntry.ABSENT))
    }

    /**
     * SOURCE LINT, and the only one here. What it covers cannot be reached from the JVM: the
     */
    @Test fun `the panel calls the decision with the row's own group and address`() {
        val screen = SOURCE.readText()
        assertTrue(
            "the participants panel must ask senderRuleEntry(isSender, trashFilePath(" +
                "accountMailboxes), senderRules, address, accountAddresses) — the account's OWN " +
                "cached folder list, the script as last read, and the addresses that ARE this " +
                "account. Without the last one the gesture is offered on the reader's own mail",
            Regex(
                "senderRuleEntry\\(\\s*isSender,\\s*trashFilePath\\(accountMailboxes\\),\\s*" +
                    "senderRules,\\s*address,\\s*accountAddresses,?\\s*\\)",
            ).containsMatchIn(screen),
        )
        assertTrue(
            "…and that list must be the ViewModel's, re-read for the message the pager landed " +
                "on: 'val accountAddresses by viewModel.accountAddresses" +
                ".collectAsStateWithLifecycle()'. A constant there, or a list read once for the " +
                "app, is a rule offered against oneself on the next account of the unified inbox",
            "val accountAddresses by viewModel.accountAddresses.collectAsStateWithLifecycle()" in screen,
        )
        assertTrue(
            "the From group must be the only one flagged as the sender",
            "ParticipantGroup(R.string.participants_from, from, isSender = true," in screen &&
                "ParticipantGroup(R.string.participants_to, to, isSender = false," in screen &&
                "ParticipantGroup(R.string.participants_cc, cc, isSender = false," in screen,
        )
        assertTrue(
            "each row must ask the decision for ITS OWN group and address, not once for the panel",
            "senderRule.entryFor(isSender, addr.email)" in screen,
        )
        assertTrue(
            "the entry may only be tappable in the one state that means 'go ahead'",
            "enabled = entry == SenderRuleEntry.OFFERED," in screen,
        )
        assertTrue(
            "tapping it must open the confirmation and write nothing: 'confirmRule = true'",
            "onClick = { menuOpen = false; confirmRule = true }," in screen,
        )
        assertTrue(
            "and only the dialog's confirm button may write, as 'senderRule.onBlock(addr.email)'",
            "confirmRule = false; senderRule.onBlock(addr.email)" in screen,
        )
        assertEquals(
            "…exactly once in the file: a second write path is a confirmation that can be walked past",
            1,
            Regex(Regex.escape("senderRule.onBlock(")).findAll(screen).count(),
        )
    }

    /**
     * Both rules below were written after WATCHING their mutation survive the whole campaign.
     */
    @Test fun `opening the panel is what reads the account's script`() {
        // The mutation that survived: delete the one line that arms the read. The state then
        // stays Unread for ever — which now HIDES the entry instead of offering it wrongly, so
        // the gesture disappears with no word anywhere, and every test above goes on passing
        // because each of them hands the decision a state directly.
        val screen = SOURCE.readText()
        assertTrue(
            "the participants panel must arm the read, as 'LaunchedEffect(Unit) { " +
                "senderRule.onOpened(); onOpened() }' — pinned WHOLE, because a `contains` on " +
                "the arming call alone is blind to it being dropped from a line that still " +
                "carries the panel's own callback. Nothing else in this screen asks for the script",
            "LaunchedEffect(Unit) { senderRule.onOpened(); onOpened() }" in screen,
        )
        assertTrue(
            "…and that callback must be the ViewModel's read: 'onOpened = viewModel::loadSenderRules'",
            "onOpened = viewModel::loadSenderRules," in screen,
        )
        assertTrue(
            "the entry's state must come from the script the panel read — 'senderRules' — and " +
                "not from a constant",
            "val senderRules by viewModel.senderRules.collectAsStateWithLifecycle()" in screen,
        )
    }

    @Test fun `the ViewModel tells 'no answer' apart from 'not asked'`() {
        // The two states R6 collapsed into one, and neither half can be reached from a JVM test.
        // A read that answers Unread where it should answer Unreachable strands the entry on any
        val vm = VIEW_MODEL.readText()
        assertTrue(
            "a read that fails must answer SenderScript.Unreachable, and a CANCELLED read must " +
                "answer nothing at all: '.getOrElseUnlessCancelled { SenderScript.Unreachable }' " +
                "(issue #99). The reader is a pager — this coroutine dies on every swipe, and " +
                "reading that as 'unreachable' answers for a message nobody is looking at",
            ".getOrElseUnlessCancelled { SenderScript.Unreachable }" in vm &&
                "getOrDefault(SenderScript" !in vm,
        )
        assertTrue(
            "and the pager's reset must put it back to 'nothing has been asked': " +
                "'_senderRules.value = SenderScript.Unread'",
            "_senderRules.value = SenderScript.Unread" in vm,
        )
        assertTrue(
            "re-opening the panel may only skip the round-trip once the server ANSWERED — " +
                "'if (_senderRules.value is SenderScript.Read) return'. Skipping on Unreachable " +
                "as well would strand the entry on one failed read until the message is left",
            "if (_senderRules.value is SenderScript.Read) return" in vm,
        )
    }

    @Test fun `the account's own addresses are re-read for every message the pager lands on`() {
        // The reader crosses ACCOUNTS in the unified inbox. Own addresses read once, or read
        // late, are the PREVIOUS account's — and "is this me?" answered with somebody else's
        // identities is the state R6 was found in. ReaderStateResetOnLoadTest holds the general
        // rule; this one holds what the value must be.
        val vm = VIEW_MODEL.readText()
        assertTrue(
            "load() must refill the account's own addresses: '_accountAddresses.value = " +
                "ownAddresses()'",
            "_accountAddresses.value = ownAddresses()" in vm,
        )
        assertTrue(
            "…and the whole expression, not its opening: 'accountAddresses(store.identities(" +
                "accountId), credentials()?.username)'. What each identity CONTRIBUTES is " +
                "accountAddresses' business and AccountAddressesTest runs it; what this pins is " +
                "that the identities asked for are THIS message's account's, and that the login " +
                "goes in with them",
            "accountAddresses(store.identities(accountId), credentials()?.username)" in vm,
        )
    }

    @Test fun `the confirmation names the address it is about to file`() {
        // The other survivor: keep the dialog, keep its title, drop the address it interpolates.
        // The reader is then asked to confirm a rule about nobody in particular — on a panel
        // that lists several people, one row apart.
        //
        // NOT pinned here, and it took a mutation to notice: WHICH of the dialog's two action
        // slots the write leaves from. The rules below read the file as one string, so swapping
        val screen = SOURCE.readText()
        assertTrue(
            "the dialog's title must name the address: 'stringResource(R.string." +
                "sender_volume_block_title, addr.email)'",
            "stringResource(R.string.sender_volume_block_title, addr.email)" in screen,
        )
        assertTrue(
            "and its body must say where the rule can be undone, in the app's own localised " +
                "labels rather than an English path typed into nine translations",
            "R.string.sender_volume_block_body," in screen,
        )
        // ORDERED. The three ids used to be checked with three unordered `in`: swapping the two
        // argument lines kept all three true, and the sentence that tells the reader how to undo
        assertTrue(
            "…and in the order the sentence reads: %1\$s is the settings entry, %2\$s the " +
                "Filters screen INSIDE it. The two stringResource calls are not interchangeable",
            PATH_ARGS.containsMatchIn(screen),
        )
    }

    @Test fun `the reader's write path is addBlockRule, with a read taken at the moment of writing`() {
        // Same rule as the per-sender screen's, for the same reason: saveFilterRules rewrites the
        // WHOLE script, so a save built on anything but a fresh successful read deletes the
        // account's filters instead of adding one. And the foreign-script refusal lives inside
        // addBlockRule, where no caller can pass an argument that switches it off.
        val vm = VIEW_MODEL.readText()
        assertTrue(
            "MessageViewModel.blockSender must go through addBlockRule(...)",
            "val outcome = addBlockRule(" in vm,
        )
        assertTrue(
            "its load must be the repository call itself — 'load = { repo.loadFilterRules(" +
                "credentials) }' — never a snapshot taken when the message opened",
            "load = { repo.loadFilterRules(credentials) }," in vm,
        )
        val saves = vm.lines().filter { "saveFilterRules(" in it && !it.trimStart().startsWith("*") }
        assertEquals(
            "every saveFilterRules call in this ViewModel must be addBlockRule's 'save =' " +
                "callback. Found:\n" + saves.joinToString("\n"),
            saves.size,
            saves.count { "save = " in it },
        )
        assertTrue(
            "the Trash must be named from the account's own cached folders",
            "val trashPath = trashFilePath(accountMailboxes.value)" in vm,
        )
    }

    private companion object {
        /**
         * The two arguments of a "Settings → Filters" sentence, IN THAT ORDER, each alone on its
         */
        val PATH_ARGS = Regex(
            """\n[ \t]*stringResource\(R\.string\.inbox_settings\),[ \t]*""" +
                """\n[ \t]*stringResource\(R\.string\.settings_filters_title\),[ \t]*(?=\n|${'$'})""",
        )

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }
        val SOURCE: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt") }
        val VIEW_MODEL: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
    }
}
