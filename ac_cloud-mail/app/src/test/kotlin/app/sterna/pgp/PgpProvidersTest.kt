package app.sterna.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Executes the provider decision. Every expectation below is written out by hand: a test that
 */
class PgpProvidersTest {

    // --- eligible() -------------------------------------------------------------------------

    @Test fun `APG is not an eligible provider`() {
        assertEquals(
            "APG answers the same service intent with an incompatible implementation; binding it " +
                "breaks PGP instead of enabling it.",
            listOf("com.pgpony.android", "org.sufficientlysecure.keychain"),
            PgpProviders.eligible(
                listOf(
                    "org.sufficientlysecure.keychain",
                    "org.thialfihar.android.apg",
                    "com.pgpony.android",
                ),
            ),
        )
    }

    @Test fun `APG alone leaves no provider at all`() {
        assertEquals(emptyList<String>(), PgpProviders.eligible(listOf("org.thialfihar.android.apg")))
    }

    @Test fun `duplicates collapse and the result is ordered by package name`() {
        assertEquals(
            "the package manager can list the same service twice and in any order; the answer " +
                "must not depend on that.",
            listOf("com.pgpony.android", "org.sufficientlysecure.keychain", "zz.last.provider"),
            PgpProviders.eligible(
                listOf(
                    "zz.last.provider",
                    "org.sufficientlysecure.keychain",
                    "com.pgpony.android",
                    "org.sufficientlysecure.keychain",
                ),
            ),
        )
    }

    @Test fun `nothing installed is an empty list, not a crash`() {
        assertEquals(emptyList<String>(), PgpProviders.eligible(emptyList()))
    }

    // --- preferredDefault() -----------------------------------------------------------------

    @Test fun `OpenKeychain wins over a package that sorts before it`() {
        // com.pgpony.android < org.sufficientlysecure.keychain alphabetically. Picking the first of
        // the sort here would move every existing PGP user off OpenKeychain on update, silently,
        // onto a provider that does not hold their keys.
        assertEquals(
            "the default no longer prefers OpenKeychain: an existing user is switched to another " +
                "provider by an update alone.",
            "org.sufficientlysecure.keychain",
            PgpProviders.preferredDefault(
                listOf("com.pgpony.android", "org.sufficientlysecure.keychain"),
            ),
        )
    }

    @Test fun `OpenKeychain wins whatever order the list arrives in`() {
        assertEquals(
            "org.sufficientlysecure.keychain",
            PgpProviders.preferredDefault(
                listOf("org.sufficientlysecure.keychain", "com.pgpony.android"),
            ),
        )
    }

    @Test fun `the debug variant is the fallback when the release build is absent`() {
        assertEquals(
            "org.sufficientlysecure.keychain.debug",
            PgpProviders.preferredDefault(
                listOf("com.pgpony.android", "org.sufficientlysecure.keychain.debug"),
            ),
        )
    }

    @Test fun `the release build beats the debug variant`() {
        assertEquals(
            "org.sufficientlysecure.keychain",
            PgpProviders.preferredDefault(
                listOf("org.sufficientlysecure.keychain.debug", "org.sufficientlysecure.keychain"),
            ),
        )
    }

    @Test fun `without OpenKeychain the first in package-name order is taken`() {
        assertEquals(
            "com.pgpony.android",
            PgpProviders.preferredDefault(listOf("com.pgpony.android", "zz.last.provider")),
        )
    }

    @Test fun `without OpenKeychain the pick still ignores the order it was handed`() {
        assertEquals(
            "com.pgpony.android",
            PgpProviders.preferredDefault(listOf("zz.last.provider", "com.pgpony.android")),
        )
    }

    @Test fun `a single third-party provider is the default`() {
        assertEquals("com.pgpony.android", PgpProviders.preferredDefault(listOf("com.pgpony.android")))
    }

    @Test fun `no provider installed means no default`() {
        assertNull(PgpProviders.preferredDefault(emptyList()))
    }
}
