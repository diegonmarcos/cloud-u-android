package com.x8bit.bitwarden.data.credentials.manager

import com.x8bit.bitwarden.data.platform.manager.ResourceCacheManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull

class RpIdOriginMatcherTest {

    private val resourceCacheManager = mockk<ResourceCacheManager> {
        every { domainExceptionSuffixes } returns emptyList()
        every { domainNormalSuffixes } returns listOf("com", "io", "github.io", "uk", "co.uk")
        every { domainWildCardSuffixes } returns emptyList()
    }
    private val matcher = RpIdOriginMatcher(resourceCacheManager)

    @Test
    fun `hostOf should read the host from an origin or a bare host`() {
        assertEquals("www.squarespace.com", matcher.hostOf("https://www.squarespace.com"))
        assertEquals("www.squarespace.com", matcher.hostOf("https://WWW.Squarespace.com:8443/x"))
        assertEquals("squarespace.com", matcher.hostOf("squarespace.com"))
        assertNull(matcher.hostOf(""))
    }

    @Test
    fun `an rpId equal to the origin host is valid`() {
        assertTrue(matcher.isRpIdValidForOrigin("www.squarespace.com", "https://www.squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("SQUARESPACE.COM", "https://squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("squarespace.com.", "https://squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("squarespace.com", "https://squarespace.com:8443"))
    }

    @Test
    fun `an rpId that is a registrable suffix of the origin host is valid`() {
        assertTrue(matcher.isRpIdValidForOrigin("squarespace.com", "https://www.squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("squarespace.com", "https://a.b.squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("b.squarespace.com", "https://a.b.squarespace.com"))
        assertTrue(matcher.isRpIdValidForOrigin("example.co.uk", "https://shop.example.co.uk"))
    }

    @Test
    fun `an rpId that is a subdomain of the origin host or an unrelated host is invalid`() {
        assertFalse(matcher.isRpIdValidForOrigin("login.squarespace.com", "https://squarespace.com"))
        assertFalse(matcher.isRpIdValidForOrigin("evil.com", "https://www.squarespace.com"))
        assertFalse(matcher.isRpIdValidForOrigin("", "https://www.squarespace.com"))
    }

    @Test
    fun `an rpId that only matches at a label boundary is invalid`() {
        assertFalse(matcher.isRpIdValidForOrigin("squarespace.com", "https://evilsquarespace.com"))
        assertFalse(
            matcher.isRpIdValidForOrigin("squarespace.com", "https://squarespace.com.evil.com"),
        )
    }

    @Test
    fun `an rpId that is a public suffix is invalid`() {
        assertFalse(matcher.isRpIdValidForOrigin("com", "https://www.squarespace.com"))
        assertFalse(matcher.isRpIdValidForOrigin("co.uk", "https://shop.example.co.uk"))
        assertFalse(matcher.isRpIdValidForOrigin("uk", "https://shop.example.co.uk"))
        assertFalse(matcher.isRpIdValidForOrigin("github.io", "https://user.github.io"))
        assertFalse(matcher.isRpIdValidForOrigin("io", "https://user.github.io"))
    }

    @Test
    fun `a host that is itself a public suffix only matches an identical rpId`() {
        assertTrue(matcher.isRpIdValidForOrigin("github.io", "https://github.io"))
        assertFalse(matcher.isRpIdValidForOrigin("io", "https://github.io"))
    }

    @Test
    fun `an IP address origin only matches the identical rpId`() {
        assertTrue(matcher.isRpIdValidForOrigin("127.0.0.1", "https://127.0.0.1"))
        assertFalse(matcher.isRpIdValidForOrigin("0.0.1", "https://127.0.0.1"))
    }
}
