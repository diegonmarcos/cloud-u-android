package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcFillOrder
import com.diegonmarcos.superapp.browser.AcFillStep
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcSite
import com.diegonmarcos.superapp.browser.AcSource
import com.diegonmarcos.superapp.browser.AcVaultItem
import com.diegonmarcos.superapp.browser.AcVaultMerge
import com.diegonmarcos.superapp.browser.AcVaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Try fill, then let Cloud Vault overwrite it": order, domain matching and the final value. */
class AcFillOrderTest {

    @Test
    fun `local fills first and Cloud Vault comes after it`() {
        assertEquals(listOf(AcFillStep.LOCAL, AcFillStep.VAULT), AcFillOrder.steps(localPicked = true, vault = AcVaultState.READY))
    }

    @Test
    fun `the vault is never asked when it is off, unusable or the tab is private`() {
        assertEquals(listOf(AcFillStep.LOCAL), AcFillOrder.steps(true, AcVaultState.OFF))
        assertEquals(listOf(AcFillStep.LOCAL), AcFillOrder.steps(true, AcVaultState.NOT_SERVICE))
        assertEquals(listOf(AcFillStep.LOCAL), AcFillOrder.steps(true, AcVaultState.READY, incognito = true))
        assertEquals(listOf(AcFillStep.VAULT), AcFillOrder.steps(false, AcVaultState.READY))
        assertTrue(AcFillOrder.steps(false, AcVaultState.OFF).isEmpty())
    }

    @Test
    fun `the vault value overwrites the local fill, an empty vault value does not`() {
        assertEquals("vault-pw", AcFillOrder.finalValue("local-pw", "vault-pw"))
        assertEquals("local-pw", AcFillOrder.finalValue("local-pw", ""))
        assertEquals("local-pw", AcFillOrder.finalValue("local-pw", null))
    }

    @Test
    fun `domain matching is by registrable domain and refuses lookalikes`() {
        assertTrue(AcSite.same("login.example.com", "https://www.example.com/x"))
        assertTrue(AcSite.same("shop.example.co.uk", "example.co.uk"))
        assertFalse(AcSite.same("example.com", "example.com.evil.net"))
        assertFalse(AcSite.same("example.co.uk", "other.co.uk"))
        assertFalse(AcSite.same("", ""))
    }

    @Test
    fun `local then vault on the same site ends with the vault value, other sites are untouched`() {
        val local = listOf(AcLogin("l1", "example.com", "ana", "local-pw", source = AcSource.LOCAL, updatedAt = 1),
            AcLogin("l2", "other.org", "ana", "other-pw", source = AcSource.LOCAL, updatedAt = 1))
        val (next, m) = AcVaultMerge.login(local, AcVaultItem.Login("www.example.com", "ana", "vault-pw"), now = 5)
        assertEquals("vault-pw", m.entry.password); assertEquals(AcSource.VAULT, m.entry.source)
        assertEquals("other-pw", next.first { it.id == "l2" }.password)
    }
}
