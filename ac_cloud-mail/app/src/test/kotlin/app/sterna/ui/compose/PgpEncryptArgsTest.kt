package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who signs, and who can read — the only thing the "encrypt without signing" mode changes in the
 */
class PgpEncryptArgsTest {

    private val self = 0x1122334455667788L
    private val bob = 0x00000000AAAA0001L
    private val carol = 0x00000000AAAA0002L

    // --- who signs -------------------------------------------------------------------------------

    @Test fun encryptSignsWithTheAccountKey() {
        assertEquals(
            "ENCRYPT must keep signing: it is the mode that was there before, and the one the " +
                "account's encrypt-by-default reaches.",
            self,
            pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob)).signKeyId,
        )
    }

    @Test fun encryptUnsignedAsksForNoSignatureAtAll() {
        assertNull(
            "ENCRYPT_UNSIGNED must pass signKeyId = null. That null is the whole mode: it is what " +
                "makes the engine take ACTION_ENCRYPT instead of ACTION_SIGN_AND_ENCRYPT. Any key " +
                "here and the message goes out signed — the opposite of what the composer showed.",
            pgpEncryptArgs(PgpMode.ENCRYPT_UNSIGNED, self, listOf(bob)).signKeyId,
        )
    }

    // --- who can read ----------------------------------------------------------------------------

    @Test fun encryptUnsignedStillEncryptsToSelf() {
        val keys = pgpEncryptArgs(PgpMode.ENCRYPT_UNSIGNED, self, listOf(bob, carol)).recipientKeyIds
        assertTrue(
            "the sender's own key is not among the recipients of an unsigned encrypted message: " +
                "the copy filed in Sent becomes unreadable, on the sender's own device, " +
                "definitively — the plaintext is kept nowhere else and no re-encryption is " +
                "possible after the fact. Keys passed were ${keys.toList()}",
            keys.contains(self),
        )
    }

    @Test fun encryptAlsoEncryptsToSelf() {
        val keys = pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob)).recipientKeyIds
        assertTrue(
            "the signed mode lost its encrypt-to-self: its Sent copies become unreadable too, " +
                "definitively. Keys passed were ${keys.toList()}",
            keys.contains(self),
        )
    }

    @Test fun everyRecipientKeyIsPassedInBothModes() {
        for (mode in listOf(PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED)) {
            val keys = pgpEncryptArgs(mode, self, listOf(bob, carol)).recipientKeyIds.toList()
            assertEquals(
                "$mode dropped or reordered a recipient: a key missing here is a recipient who " +
                    "cannot read the message at all.",
                listOf(bob, carol, self),
                keys,
            )
        }
    }

    @Test fun theAccountKeyIsNotPassedTwiceWhenItIsAlsoARecipient() {
        // Writing to oneself, or to a list one is on. A duplicate is not fatal, but it is the
        // signature of a list built by appending without thinking, and OpenKeychain shows the
        // recipients it encrypts to.
        val keys = pgpEncryptArgs(PgpMode.ENCRYPT_UNSIGNED, self, listOf(bob, self)).recipientKeyIds
        assertEquals(listOf(bob, self), keys.toList())
    }

    // --- the two arguments together, as the send site passes them --------------------------------

    @Test fun theUnsignedModeChangesTheSignerAndNothingElse() {
        val signed = pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob, carol))
        val unsigned = pgpEncryptArgs(PgpMode.ENCRYPT_UNSIGNED, self, listOf(bob, carol))
        assertEquals(
            "the recipient set must be identical in both modes — the ONLY difference between " +
                "them is the signature.",
            signed.recipientKeyIds.toList(),
            unsigned.recipientKeyIds.toList(),
        )
        assertEquals(self, signed.signKeyId)
        assertNull(unsigned.signKeyId)
    }

    @Test fun equalsLooksAtTheKeysAndNotAtTheArrayIdentity() {
        // PgpEncryptArgs holds a LongArray: with the generated equals, two identical results
        // compare unequal and every assertEquals above would pass by accident on any change.
        assertEquals(
            pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob)),
            pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob)),
        )
        assertTrue(
            "two different signers must not compare equal",
            pgpEncryptArgs(PgpMode.ENCRYPT, self, listOf(bob)) !=
                pgpEncryptArgs(PgpMode.ENCRYPT_UNSIGNED, self, listOf(bob)),
        )
    }

    @Test fun aModeThatDoesNotEncryptIsRefused() {
        // Not defensive noise: the send site branches on the mode, then suspends in the provider,
        // then asks for the signer. If the mode moves to OFF under that suspension, answering
        for (mode in listOf(PgpMode.OFF, PgpMode.SIGN)) {
            assertThrows(IllegalArgumentException::class.java) {
                pgpEncryptArgs(mode, self, listOf(bob))
            }
        }
    }
}
