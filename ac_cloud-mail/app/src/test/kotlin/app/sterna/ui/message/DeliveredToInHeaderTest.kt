package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [deliveredToInHeader], EXECUTED — whether the open message's header gets a third line naming
 */
class DeliveredToInHeaderTest {

    /** Two entries that really are two addresses. */
    private val twoAddresses = listOf("moi@ex.test", "alias@ex.test")

    @Test fun `nothing delivered means no line`() {
        assertNull(deliveredToInHeader(null, twoAddresses, false))
    }

    /** A mailing list or a Bcc delivery names no address of the account: an empty label is worse
     *  than no label. */
    @Test fun `a blank delivery means no line`() {
        assertNull(deliveredToInHeader("", twoAddresses, false))
        assertNull(deliveredToInHeader("   ", twoAddresses, false))
    }

    /**
     * THE trap this guard exists for. `accountAddresses(identities, username)` is
     */
    @Test fun `one address listed twice is still one address`() {
        assertNull(deliveredToInHeader("moi@ex.test", listOf("moi@ex.test", "moi@ex.test"), false))
    }

    /** The same trap as the store actually spells it: the login and the identity differ in case. */
    @Test fun `one address listed twice in two casings is still one address`() {
        assertNull(deliveredToInHeader("moi@ex.test", listOf("Moi@Ex.test", "moi@ex.test"), false))
    }

    @Test fun `a single address means no line`() {
        assertNull(deliveredToInHeader("moi@ex.test", listOf("moi@ex.test"), false))
        assertNull(deliveredToInHeader("moi@ex.test", emptyList(), false))
    }

    /** A blank entry is not an address and does not make an account multi-address. */
    @Test fun `a blank entry does not count as a second address`() {
        assertNull(deliveredToInHeader("moi@ex.test", listOf("moi@ex.test", "   "), false))
    }

    @Test fun `two distinct addresses give the line`() {
        assertEquals("alias@ex.test", deliveredToInHeader("alias@ex.test", twoAddresses, false))
    }

    /** Three entries collapsing to two distinct addresses is still a multi-address account. */
    @Test fun `three entries for two distinct addresses give the line`() {
        assertEquals(
            "alias@ex.test",
            deliveredToInHeader("alias@ex.test", listOf("moi@ex.test", "alias@ex.test", "moi@ex.test"), false),
        )
    }

    /**
     * The spelling the message used is the spelling shown. Case folding is how two entries are
     */
    @Test fun `the delivered address keeps its own spelling`() {
        assertEquals(
            "Alias@Ex.Test",
            deliveredToInHeader("Alias@Ex.Test", listOf("moi@ex.test", "alias@ex.test"), false),
        )
    }

    /**
     * Only entries that look like an address are counted. An identity may carry an empty or
     * non-address e-mail, and two distinct STRINGS are not two addresses.
     */
    @Test fun `an entry with no at-sign is not a second address`() {
        assertNull(deliveredToInHeader("theo@ex.test", listOf("theo@ex.test", "theo"), false))
    }

    /**
     * THE reason this parameter is the IDENTITY list and not `accountAddresses`. Shared hosting
     */
    @Test fun `a login that is a different address is not in this list at all`() {
        assertNull(deliveredToInHeader("moi@mondomaine.tld", listOf("moi@mondomaine.tld"), false))
    }

    /**
     * An outgoing message has no arrival. Sent and Drafts are shown by their RECIPIENTS, and
     */
    @Test fun `the reader's own message never gets an arrival line`() {
        assertNull(deliveredToInHeader("alias@ex.test", twoAddresses, true))
    }

    /** The same message, incoming, does get it: [ownMessage] is the only difference. */
    @Test fun `the same message not marked own does get the line`() {
        assertEquals("alias@ex.test", deliveredToInHeader("alias@ex.test", twoAddresses, false))
    }

    /**
     * The label asserts the address is YOURS. Today's only caller cannot produce anything else,
     */
    @Test fun `an address that is not one of yours gets no line`() {
        assertNull(deliveredToInHeader("tiers@ailleurs.test", listOf("moi@ex.test", "alias@ex.test"), false))
    }

    /** Belonging is decided case-insensitively, like every other self-comparison here. */
    @Test fun `an own address in another casing still belongs`() {
        assertEquals(
            "Alias@EX.test",
            deliveredToInHeader("Alias@EX.test", listOf("moi@ex.test", "alias@ex.test"), false),
        )
    }
}
