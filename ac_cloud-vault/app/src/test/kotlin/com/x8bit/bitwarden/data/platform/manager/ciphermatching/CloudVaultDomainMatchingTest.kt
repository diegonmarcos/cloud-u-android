package com.x8bit.bitwarden.data.platform.manager.ciphermatching

import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.vault.CipherListView
import com.bitwarden.vault.CipherListViewType
import com.bitwarden.vault.LoginListView
import com.bitwarden.vault.LoginUriView
import com.bitwarden.vault.UriMatchType
import com.x8bit.bitwarden.data.platform.manager.ResourceCacheManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.DomainsData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The vault tier's "never cross-domain" promise, against the REAL matcher and the REAL public
 * suffix parsing (no static mocks of getDomainOrNull, unlike CipherMatchingManagerTest): a small
 * fake suffix list stands in for the bundled one. Each case is one saved login and one page.
 */
class CloudVaultDomainMatchingTest {

    private val publicSuffixes = object : ResourceCacheManager {
        override val domainExceptionSuffixes: List<String> = emptyList()
        override val domainNormalSuffixes: List<String> = listOf("com", "net", "org", "uk", "co.uk")
        override val domainWildCardSuffixes: List<String> = emptyList()
    }

    private fun manager(
        defaultMatch: com.x8bit.bitwarden.data.platform.repository.model.UriMatchType =
            com.x8bit.bitwarden.data.platform.repository.model.UriMatchType.DOMAIN,
        equivalentDomains: List<List<String>> = emptyList(),
    ): CipherMatchingManager {
        val settings: SettingsRepository = mockk { every { defaultUriMatchType } returns defaultMatch }
        val vault: VaultRepository = mockk {
            every { domainsStateFlow } returns MutableStateFlow(
                DataState.Loaded(
                    DomainsData(
                        equivalentDomains = equivalentDomains,
                        globalEquivalentDomains = emptyList(),
                    ),
                ),
            )
        }
        return CipherMatchingManagerImpl(
            resourceCacheManager = publicSuffixes,
            settingsRepository = settings,
            vaultRepository = vault,
        )
    }

    private fun login(savedUri: String, match: UriMatchType? = null): CipherListView {
        val uriView: LoginUriView = mockk {
            every { this@mockk.match } returns match
            every { uri } returns savedUri
        }
        val loginView: LoginListView = mockk { every { uris } returns listOf(uriView) }
        return mockk { every { type } returns CipherListViewType.Login(loginView) }
    }

    private fun matches(
        page: String,
        saved: String,
        match: UriMatchType? = null,
        manager: CipherMatchingManager = manager(),
    ): Boolean = runTestResult {
        val cipher = login(saved, match)
        manager.filterCiphersForMatches(listOf(cipher), page) == listOf(cipher)
    }

    private fun runTestResult(block: suspend () -> Boolean): Boolean {
        var result = false
        runTest { result = block() }
        return result
    }

    @Test
    fun `a subdomain page matches the saved base domain, and the other way round`() {
        assertEquals(true, matches("https://login.example.com", "https://example.com"))
        assertEquals(true, matches("https://example.com", "https://accounts.example.com"))
    }

    @Test
    fun `lookalike domains never match`() {
        val lookalikes = listOf(
            "https://example.com.evil.net",
            "https://evil-example.com",
            "https://examp1e.com",
            "https://example.net",
            "https://notexample.com",
        )
        lookalikes.forEach { page ->
            assertEquals(false, matches(page, "https://example.com"), page)
        }
    }

    @Test
    fun `the public suffix decides the base domain`() {
        assertEquals(true, matches("https://shop.example.co.uk", "https://example.co.uk"))
        // Two different registrations under the same public suffix.
        assertEquals(false, matches("https://example.co.uk", "https://other.co.uk"))
    }

    @Test
    fun `http versus https - base domain ignores the scheme, exact does not`() {
        assertEquals(true, matches("http://example.com", "https://example.com"))
        assertEquals(false, matches("http://example.com", "https://example.com", UriMatchType.EXACT))
        assertEquals(true, matches("https://example.com", "https://example.com", UriMatchType.EXACT))
    }

    @Test
    fun `host matching needs the same host`() {
        assertEquals(false, matches("https://login.example.com", "https://example.com", UriMatchType.HOST))
        assertEquals(true, matches("http://login.example.com", "https://login.example.com", UriMatchType.HOST))
    }

    @Test
    fun `never-match logins are never offered`() {
        assertEquals(false, matches("https://example.com", "https://example.com", UriMatchType.NEVER))
    }

    @Test
    fun `an app matches its own package, not a website`() {
        assertEquals(true, matches("androidapp://com.example.app", "androidapp://com.example.app"))
        assertEquals(false, matches("androidapp://com.example.app", "https://example.com"))
        assertEquals(false, matches("androidapp://com.example.app", "androidapp://com.example.other"))
    }

    @Test
    fun `an app reaches a website only through an equivalent-domain mapping`() {
        val mapped = manager(equivalentDomains = listOf(listOf("example.com", "example.net")))
        assertEquals(true, matches("androidapp://com.example.app", "https://example.net", manager = mapped))
        assertEquals(false, matches("androidapp://com.other.app", "https://example.net", manager = mapped))
    }
}
