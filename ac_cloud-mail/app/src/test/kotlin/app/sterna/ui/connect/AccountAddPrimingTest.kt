package app.sterna.ui.connect

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding an account primed its cache BEFORE the account existed (#121).
 */
class AccountAddPrimingTest {

    /** What a run of [addAccountThenPrime] did, in order. */
    private class Recorder {
        val steps = mutableListOf<String>()
        var validated: AccountCredentials? = null
        var primed: AccountCredentials? = null
    }

    /**
     * The account list as `AccountStore` really keeps it, in the two respects this test needs:
     */
    private class FakeAccountStore(vararg initial: String) {
        val accounts = initial.toMutableList()
        var current: String? = initial.lastOrNull()

        fun add(id: String): String {
            accounts += id
            current = id
            return id
        }

        fun remove(id: String) {
            accounts -= id
            if (current == id || accounts.none { it == current }) current = accounts.firstOrNull()
        }
    }

    private suspend fun attemptAdd(
        probe: AccountCredentials,
        rec: Recorder,
        validateFails: Throwable? = null,
        primeFails: Throwable? = null,
        persist: suspend () -> AddedAccount,
    ): Result<PrimedAccount> = runCatching {
        addAccountThenPrime(
            probe = probe,
            validate = {
                rec.steps += "validate"
                rec.validated = it
                validateFails?.let { e -> throw e }
            },
            persist = {
                rec.steps += "persist"
                persist()
            },
            prime = {
                rec.steps += "prime"
                rec.primed = it
                primeFails?.let { e -> throw e }
            },
        )
    }

    private fun newAccount(id: String) = suspend { AddedAccount(id, created = true) }

    // The three probes, built exactly as the three screens build them: no id anywhere.
    private val jmapPassword = AccountCredentials("https://jmap.example/", "alex@example.org", "pw")
    private val jmapToken = AccountCredentials(
        "https://api.fastmail.com/jmap/session", "alex@fastmail.com", "tok", authType = AuthType.API_TOKEN,
    )
    private val imap = AccountCredentials(
        server = "",
        username = "alex@mail.ru",
        password = "pw",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint("imap.mail.ru", 993, ConnectionSecurity.TLS),
        smtp = MailEndpoint("smtp.mail.ru", 465, ConnectionSecurity.TLS),
    )

    // -- the core rule ---------------------------------------------------------------------------

    @Test fun `no add path can prime the cache under an empty account id`() = runTest {
        for ((name, probe) in listOf("JMAP password" to jmapPassword, "JMAP token" to jmapToken, "IMAP" to imap)) {
            assertEquals(
                "$name: the probe is expected to carry no id — that is the whole trap being closed",
                "", probe.id,
            )
            val rec = Recorder()
            val result = attemptAdd(probe, rec, persist = newAccount("acct-$name"))
            assertEquals("$name: the add should have succeeded", "acct-$name", result.getOrNull()?.id)
            val primed = rec.primed
            assertNotNull("$name: the cache was never primed at all", primed)
            assertEquals(
                "$name: the cache was primed under the id `${primed!!.id}` — every row written by " +
                    "this add lands under that account id, and an empty one belongs to no account",
                "acct-$name", primed.id,
            )
        }
    }

    @Test fun `a persist that yields no id stops the add instead of priming`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(jmapPassword, rec) { AddedAccount("", created = true) }
        assertTrue("a blank id must not be primed with, it can only be a bug", result.isFailure)
        assertNull("nothing may be written under a blank account id", rec.primed)
        assertEquals(listOf("validate", "persist"), rec.steps)
    }

    // -- the witness: the fix must still prime, and under the right account ----------------------

    @Test fun `a successful add proves the credentials first, then creates, then primes`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(imap, rec, persist = newAccount("acct-imap"))
        assertEquals("acct-imap", result.getOrNull()?.id)
        assertNull("a clean add reports no priming failure", result.getOrNull()?.primeFailure)
        assertEquals(
            "the order is the fix: validating after persisting would leave an account behind on a " +
                "typo, priming before persisting is the bug itself",
            listOf("validate", "persist", "prime"), rec.steps,
        )
        val primed = rec.primed!!
        assertEquals("acct-imap", primed.id)
        assertEquals("only the id changes — the connection details must survive", imap.copy(id = "acct-imap"), primed)
        assertEquals("the credentials proven are the ones typed", imap, rec.validated)
    }

    // -- a rejected sign-in still leaves nothing behind -------------------------------------------

    @Test fun `credentials that fail validation create no account and write nothing`() = runTest {
        val rec = Recorder()
        val store = FakeAccountStore("acct-a", "acct-b", "acct-c")
        val boom = IllegalStateException("LOGIN failed")
        val result = attemptAdd(jmapPassword, rec, validateFails = boom) {
            AddedAccount(store.add("acct-never"), created = true)
        }
        assertEquals("the rejection must reach the screen unchanged", boom, result.exceptionOrNull())
        assertEquals(
            "a rejected sign-in must not reach the account store at all — `Only persist once we " +
                "know they work` is the rule this fix had to keep, and the ONLY thing that keeps " +
                "a mistyped password from leaving an account behind",
            listOf("validate"), rec.steps,
        )
        assertEquals(
            "a rejected sign-in left an account behind",
            listOf("acct-a", "acct-b", "acct-c"), store.accounts,
        )
        assertNull(rec.primed)
    }

    // -- a priming failure on a proven account: the account stays ---------------------------------

    @Test fun `an account created by this add survives a failed priming`() = runTest {
        val rec = Recorder()
        val store = FakeAccountStore("acct-a", "acct-b", "acct-c")
        val hiccup = IllegalStateException("no mailboxes")
        val result = attemptAdd(jmapPassword, rec, primeFails = hiccup) {
            AddedAccount(store.add("acct-new"), created = true)
        }
        assertEquals(
            "the credentials were proven — a hiccup while loading the first page of mail must not " +
                "throw the account away; its cache fills on the next refresh",
            listOf("acct-a", "acct-b", "acct-c", "acct-new"), store.accounts,
        )
        assertEquals("acct-new", result.getOrNull()?.id)
        assertSame(
            "the failure must reach the caller to be logged, not vanish",
            hiccup, result.getOrNull()?.primeFailure,
        )
        assertEquals(listOf("validate", "persist", "prime"), rec.steps)
    }

    @Test fun `a failed priming does not move the user to another account`() = runTest {
        val rec = Recorder()
        // Three accounts, the user is reading the third one.
        val store = FakeAccountStore("acct-a", "acct-b", "acct-c")
        assertEquals("acct-c", store.current)
        attemptAdd(jmapPassword, rec, primeFails = IllegalStateException("network hiccup")) {
            AddedAccount(store.add("acct-new"), created = true)
        }
        assertEquals(
            "adding an account makes it current; a failed priming used to remove it, and removing " +
                "the CURRENT account falls back to the FIRST remaining one — so a hiccup on a " +
                "fourth add silently moved the user from her third account to her first, while the " +
                "screen kept showing the third",
            "acct-new", store.current,
        )
    }

    @Test fun `leaving the screen mid-priming is not an add failure`() = runTest {
        val rec = Recorder()
        val store = FakeAccountStore()
        val result = attemptAdd(jmapPassword, rec, primeFails = CancellationException("screen left")) {
            AddedAccount(store.add("acct-new"), created = true)
        }
        assertTrue(
            "cancellation must unwind, not be swallowed into a normal outcome: the coroutine is " +
                "gone and its caller must not report it as an error the user typed",
            result.exceptionOrNull() is CancellationException,
        )
        assertEquals(
            "and the account it had already created stays — cancelling is not rejecting",
            listOf("acct-new"), store.accounts,
        )
    }

    // -- re-adding an existing account (token path) -----------------------------------------------

    @Test fun `re-adding a token account primes the account it found, without creating another`() = runTest {
        val rec = Recorder()
        val result = attemptAdd(jmapToken, rec) { AddedAccount("acct-existing", created = false) }
        assertEquals("the re-add must resolve to the account already there", "acct-existing", result.getOrNull()?.id)
        assertEquals("acct-existing", rec.primed?.id)
    }

    @Test fun `a failed priming never touches an account this add did not create`() = runTest {
        val rec = Recorder()
        val store = FakeAccountStore("acct-existing")
        val result = attemptAdd(jmapToken, rec, primeFails = IllegalStateException("server hiccup")) {
            AddedAccount("acct-existing", created = false)
        }
        assertEquals(
            "the account existed before this attempt — a transient failure while re-authenticating " +
                "it must not delete the user's account",
            listOf("acct-existing"), store.accounts,
        )
        assertEquals(
            "and the add still resolves to that account: a re-auth whose first refresh hiccups is " +
                "not a failed add",
            "acct-existing", result.getOrNull()?.id,
        )
    }
}
