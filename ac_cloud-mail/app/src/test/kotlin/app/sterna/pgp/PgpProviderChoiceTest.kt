package app.sterna.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the two decisions the provider PICKER rests on: which package a stored choice resolves
 */
class PgpProviderChoiceTest {

    private val keychain = "org.sufficientlysecure.keychain"
    private val pgpony = "com.pgpony.android"

    // --- resolve() --------------------------------------------------------------------------

    @Test fun `the stored choice wins over the default, or the picker does nothing`() {
        // The reporter of #151 has BOTH installed and is moving off OpenKeychain. OpenKeychain
        // outranks everything in preferredDefault, so consulting the default first would leave
        // Sterna calling OpenKeychain for ever while the screen says PGPony.
        assertEquals(
            "the stored choice is ignored: the selector is decorative and #151 is not fixed.",
            "com.pgpony.android",
            PgpProviders.resolve(listOf(pgpony, keychain), "com.pgpony.android"),
        )
    }

    @Test fun `a choice whose app was uninstalled falls back instead of being returned`() {
        // Returning it would bind a package that is not there: PGP dead, with a working provider
        // sitting right beside it. That is the original defect, re-created by its own fix.
        assertEquals(
            "an uninstalled choice is still returned; the engine then binds nothing and PGP is " +
                "dead even though another provider is installed.",
            "org.sufficientlysecure.keychain",
            PgpProviders.resolve(listOf(keychain), "com.pgpony.android"),
        )
    }

    @Test fun `an uninstalled choice falls back to OpenKeychain's rank, not to the sort`() {
        assertEquals(
            "org.sufficientlysecure.keychain",
            PgpProviders.resolve(listOf("aaa.first.in.sort", keychain), "com.pgpony.android"),
        )
    }

    @Test fun `no choice expressed is every existing user, and they keep OpenKeychain`() {
        assertEquals(
            "an upgrading user with keys in OpenKeychain is moved onto another provider without " +
                "a word, and their keys are not there.",
            "org.sufficientlysecure.keychain",
            PgpProviders.resolve(listOf(pgpony, keychain), null),
        )
    }

    @Test fun `no choice and one provider takes that one, with nothing to ask`() {
        assertEquals("com.pgpony.android", PgpProviders.resolve(listOf(pgpony), null))
    }

    @Test fun `nothing installed resolves to nothing, chosen or not`() {
        assertNull(PgpProviders.resolve(emptyList(), null))
        assertNull(
            "a choice conjures a provider out of an empty device; the engine binds a package " +
                "that is not installed.",
            PgpProviders.resolve(emptyList(), "com.pgpony.android"),
        )
    }

    // --- switchErasesKeys() -----------------------------------------------------------------

    @Test fun `picking a different provider erases the signing keys`() {
        assertTrue(
            "switching keyring keeps the old key ids on screen; they name keys the newly chosen " +
                "app never emitted, and the first signature fails on a key the screen calls set.",
            PgpProviders.switchErasesKeys("org.sufficientlysecure.keychain", "com.pgpony.android"),
        )
    }

    @Test fun `picking the provider already in use erases nothing`() {
        assertFalse(
            "tapping the provider already in use destroys every account's signing key; the user " +
                "has to walk the key chooser again for having confirmed what was already true.",
            PgpProviders.switchErasesKeys("com.pgpony.android", "com.pgpony.android"),
        )
    }

    @Test fun `an unknown provider-in-use erases nothing`() {
        // null = the caller does not know what is in use. Clearing a signing key is irreversible,
        // so an unknown destroys nothing — this is the exact frame in which a tap used to wipe the
        // keys of every account for a choice that changed nothing at all.
        assertFalse(
            "a null provider-in-use is treated as a switch: a tap in the frame before the screen " +
                "has resolved anything wipes every account's signing key.",
            PgpProviders.switchErasesKeys(null, "com.pgpony.android"),
        )
    }

    // --- providerToPin() --------------------------------------------------------------------

    @Test fun `the provider in use is written down when nothing was written down yet`() {
        // The single-provider device. Nothing is ever picked (no row is offered), so without this
        // the day a second OpenPGP app is installed the default ranking takes over and Sterna
        // changes provider without a word — signing key included.
        assertEquals(
            "a device with one OpenPGP app stores nothing, so installing a second one silently " +
                "moves it onto the other provider.",
            "com.pgpony.android",
            PgpProviders.providerToPin(null, "com.pgpony.android"),
        )
    }

    @Test fun `a stored choice is never rewritten, even by the same package`() {
        assertNull(
            "an already-stored choice is written again; every pass is one more chance to " +
                "overwrite it with something else.",
            PgpProviders.providerToPin("com.pgpony.android", "com.pgpony.android"),
        )
    }

    @Test fun `a stored choice whose app is momentarily absent is NOT overwritten`() {
        // The case that matters. resolve() falls back to the default while the chosen package is
        // away (updating, work profile, storage not mounted). Pinning the fallback there destroys
        // the explicit choice for good: reinstalling the app does not bring it back, because the
        // record of what was chosen is gone.
        assertNull(
            "the fallback is pinned over an explicit choice: the user's OpenPGP app is forgotten " +
                "permanently the first time it is momentarily unavailable.",
            PgpProviders.providerToPin("com.pgpony.android", "org.sufficientlysecure.keychain"),
        )
    }

    @Test fun `nothing installed is nothing to write down`() {
        assertNull(PgpProviders.providerToPin(null, null))
    }

    // --- bindingReusable() ------------------------------------------------------------------

    @Test fun `a connection bound to another provider is never reused`() {
        // The engine keeps its binding for the process lifetime. Reusing it across a switch means
        // the new provider is never called until the app is killed: changing the setting appears
        // to do nothing at all.
        assertFalse(
            "the cached connection is reused for a different package: switching provider changes " +
                "nothing until the app restarts.",
            PgpProviders.bindingReusable("org.sufficientlysecure.keychain", "com.pgpony.android"),
        )
    }

    @Test fun `a connection bound to the target provider is reused`() {
        assertTrue(
            "every call rebinds; the provider is asked to start up on each operation.",
            PgpProviders.bindingReusable("com.pgpony.android", "com.pgpony.android"),
        )
    }

    @Test fun `nothing bound yet is not a reusable binding`() {
        assertFalse(PgpProviders.bindingReusable(null, "com.pgpony.android"))
    }
}
