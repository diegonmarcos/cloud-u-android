package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcCard
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcSite
import com.diegonmarcos.superapp.browser.AcSource
import com.diegonmarcos.superapp.browser.AcVaultItem
import com.diegonmarcos.superapp.browser.AcVaultMerge
import com.diegonmarcos.superapp.browser.AcVaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #900 VAULT WINS: when Cloud Vault has the entry its value overwrites the local one; a local-only entry is untouched. */
class AcVaultMergeTest {

    private val local = listOf(
        AcLogin("l1", "example.com", "ana", "local-pw", source = AcSource.LOCAL, updatedAt = 1),
        AcLogin("l2", "example.com", "bob", "bob-pw", source = AcSource.LOCAL, updatedAt = 2),
        AcLogin("l3", "other.org", "ana", "other-pw", source = AcSource.LOCAL, updatedAt = 3),
    )

    @Test
    fun `the vault's password overwrites the local one for the same site and user, and the entry becomes vault`() {
        val (next, m) = AcVaultMerge.login(local, AcVaultItem.Login("login.example.com", "ANA", "vault-pw"), now = 100)
        assertTrue(m.changed); assertFalse(m.created)
        assertEquals("vault-pw", m.entry.password); assertEquals(AcSource.VAULT, m.entry.source); assertEquals("l1", m.entry.id)
        assertEquals(100L, m.entry.updatedAt)
        assertEquals("the others are untouched", local.drop(1), next.drop(1))
        assertEquals(3, next.size)
    }

    @Test
    fun `the same value from the vault only flips the source, and is not a change`() {
        val (_, m) = AcVaultMerge.login(local, AcVaultItem.Login("example.com", "ana", "local-pw"), now = 100)
        assertFalse(m.changed); assertEquals(AcSource.VAULT, m.entry.source); assertEquals("local-pw", m.entry.password)
        // and a second identical merge does not move the time
        val (again, m2) = AcVaultMerge.login(listOf(m.entry), AcVaultItem.Login("example.com", "ana", "local-pw"), now = 500)
        assertFalse(m2.changed); assertEquals(100L, again.single().updatedAt)
    }

    @Test
    fun `a vault entry the local store never had is kept as a local copy marked vault`() {
        val (next, m) = AcVaultMerge.login(local, AcVaultItem.Login("https://new.example/login", "carol", "pw"), now = 7)
        assertTrue(m.created); assertEquals(4, next.size)
        assertEquals("new.example", m.entry.site); assertEquals(AcSource.VAULT, m.entry.source)
    }

    @Test
    fun `a lookalike domain never matches, a subdomain does, co-uk style suffixes are handled`() {
        assertNull(AcVaultMerge.matchLogin(local, AcVaultItem.Login("example.com.evil.test", "ana", "x")))
        assertNull(AcVaultMerge.matchLogin(local, AcVaultItem.Login("notexample.com", "ana", "x")))
        assertEquals("l1", AcVaultMerge.matchLogin(local, AcVaultItem.Login("a.b.example.com", "ana", "x"))!!.id)
        assertEquals("example.com", AcSite.registrable("https://WWW.Example.com:443/x"))
        assertEquals("shop.co.uk", AcSite.registrable("www.shop.co.uk"))
        assertEquals("192.168.0.1", AcSite.registrable("http://192.168.0.1:8080/"))
        assertTrue(AcSite.same("a.shop.co.uk", "b.shop.co.uk")); assertFalse(AcSite.same("a.co.uk", "b.co.uk"))
    }

    @Test
    fun `a vault login with no user name matches only a lone local login of the site`() {
        assertNull("two local logins on the site: ambiguous", AcVaultMerge.matchLogin(local, AcVaultItem.Login("example.com", "", "x")))
        assertEquals("l3", AcVaultMerge.matchLogin(local, AcVaultItem.Login("other.org", "", "x"))!!.id)
    }

    @Test
    fun `a card matches by its digits and the vault's details win`() {
        val cards = listOf(AcCard("c1", "Mine", "Ana G", "4111 1111 1111 1111", "01", "2026", "123", AcSource.LOCAL, 1))
        val (next, m) = AcVaultMerge.card(cards, AcVaultItem.Card("4111111111111111", "Ana Garcia", "02", "2030", ""), now = 50)
        assertFalse(m.created); assertTrue(m.changed)
        assertEquals("Ana Garcia", m.entry.holder); assertEquals("02", m.entry.expMonth); assertEquals("2030", m.entry.expYear)
        assertEquals("the vault gave no code: the local one stays", "123", m.entry.cvc)
        assertEquals(AcSource.VAULT, m.entry.source); assertEquals("Mine", m.entry.label); assertEquals(1, next.size)
        val (added, m2) = AcVaultMerge.card(cards, AcVaultItem.Card("5500000000000004"), 60)
        assertTrue(m2.created); assertEquals(2, added.size)
    }

    @Test
    fun `the vault state degrades with a reason`() {
        assertEquals(AcVaultState.OFF, AcVaultState.of(useVault = false, installed = true, frameworkEnabled = true, isService = true))
        assertEquals(AcVaultState.NOT_INSTALLED, AcVaultState.of(true, installed = false, frameworkEnabled = true, isService = true))
        assertEquals(AcVaultState.NO_FRAMEWORK, AcVaultState.of(true, true, frameworkEnabled = false, isService = true))
        assertEquals(AcVaultState.NOT_SERVICE, AcVaultState.of(true, true, true, isService = false))
        assertEquals(AcVaultState.READY, AcVaultState.of(true, true, true, true))
        assertTrue(AcVaultState.READY.usable); assertFalse(AcVaultState.NOT_SERVICE.usable)
        assertTrue(AcVaultState.NOT_SERVICE.reason.contains("autofill service"))
    }
}
