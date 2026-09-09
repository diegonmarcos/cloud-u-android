package app.sterna.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The arguments Sterna hands the OpenPGP provider, EXECUTED — not read as text.
 */
class PgpApiRequestTest {

    @Test fun `the key asked for is minimized to this account's own identity`() {
        assertEquals(
            "the public-key request changed. Every difference here leaves the phone: a lost " +
                "minimize_user_id broadcasts every user id on the keyring (private address, " +
                "former employer, pseudonym) to every correspondent on every message; a lost " +
                "minimize exports the whole keyring, signatures included; a key id that is not " +
                "the account's announces somebody else's key.",
            PgpApiRequest(
                action = "org.openintents.openpgp.action.GET_KEY",
                extras = mapOf(
                    "key_id" to 0x5944688C043ED86BL,
                    "minimize" to true,
                    "minimize_user_id" to "iris.s7@masto.top",
                ),
            ),
            publicKeyRequest(keyId = 0x5944688C043ED86BL, ownAddress = "iris.s7@masto.top"),
        )
    }

    @Test fun `no ASCII armor is requested — the header carries base64 of the binary key`() {
        // THE mutation that used to survive the whole suite. `ascii_armor` is optional and absent
        // means binary; asking for it yields base64 of an armored block — ~40 % bigger, folded, and
        // unimportable by the client that receives it, with nothing malformed to give it away.
        assertFalse(
            "ASCII armor is being asked for: what goes out is base64 of an armor block, which no " +
                "receiving client can import.",
            publicKeyRequest(1L, "iris.s7@masto.top").extras.containsKey("ascii_armor"),
        )
    }

    @Test fun `an account with no address of its own asks for no user-id filter at all`() {
        // Rather than an empty filter, whose meaning is the provider's business: "every user id" is
        // saying too much, "no key at all" would kill the announcement outright.
        assertEquals(
            mapOf("key_id" to 7L, "minimize" to true),
            publicKeyRequest(keyId = 7L, ownAddress = "   ").extras,
        )
    }

    @Test fun `a peer update carries the peer, the key, the message's own date, and never mutual`() {
        val key = byteArrayOf(0x99.toByte(), 0x01, 0x02)
        assertEquals(
            "the peer update changed. isMutual = true is a standing instruction to start " +
                "encrypting, taken from an incoming message; a date that is not the message's own " +
                "lets an old message overwrite a newer key (the provider keeps the newest).",
            PgpApiRequest(
                action = "org.openintents.openpgp.action.UPDATE_AUTOCRYPT_PEER",
                extras = mapOf(
                    "autocrypt_peer_id" to "bob@example.org",
                    "autocrypt_peer_update" to PeerUpdateFacts(
                        keyData = key,
                        effectiveDateMillis = 1_756_000_000_000L,
                        isMutual = false,
                    ),
                ),
            ),
            autocryptPeerRequest(
                peerId = "bob@example.org",
                keyData = key,
                effectiveDateMillis = 1_756_000_000_000L,
            ),
        )
    }

    @Test fun `every extra has an Intent form the engine knows how to put`() {
        // The contract between these pure maps and `OpenKeychainPgpEngine.intentFor`, which cannot
        // be built here. A fifth value type added above without a branch there throws at the
        // provider call, on a phone, in the one code path nobody can run in the JVM.
        val values = publicKeyRequest(1L, "a@b.test").extras.values +
            autocryptPeerRequest("p@b.test", byteArrayOf(1), 2L).extras.values
        assertEquals(
            "an extra was added whose type OpenKeychainPgpEngine.intentFor has no branch for.",
            emptyList<Any>(),
            values.filterNot {
                it is Boolean || it is Long || it is String || it is PeerUpdateFacts
            },
        )
    }
}
