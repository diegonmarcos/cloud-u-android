package com.diegonmarcos.superapp.network

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #860 The Store's downloader follows the active preset. Mutation-proven: make
 * Mirror's plan carry a public preset's list (or drop the system resolver from
 * the head) and the first test goes red; make [StoreDns.pick] stop naming what
 * it tried and the second does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreDnsTest {
    private val d = FleetDns.decl
    private val mirror = d.presets.first { it.kind == FleetDns.KIND_MIRROR }
    private val own = (mirror.servers + mirror.fallback).toSet()
    private val publicServers = d.presets.filter { it.kind == FleetDns.KIND_PUBLIC }
        .flatMap { it.servers + it.fallback }.toSet() - own

    @Test fun `under Mirror the system resolver comes first and no public list is ever used`() {
        val plan = StoreDns.plan(mirror, listOf("Wi-Fi", "mobile data"))
        assertEquals(StoreDns.SYSTEM, plan.first().kind)
        assertEquals(listOf(StoreDns.UNDERLYING, StoreDns.UNDERLYING), plan.drop(1).take(2).map { it.kind })
        val leaked = plan.flatMap { it.servers } .filter { it in publicServers }
        assertTrue("Mirror resolved through a fixed public list: $leaked", leaked.isEmpty())
        assertTrue("only the preset's own servers, and only last",
            plan.filter { it.kind == StoreDns.PRESET_SERVERS }.all { plan.last() == it && it.servers.toSet() == own })
    }

    @Test fun `a public preset is the VPN's list, read through the system resolver`() {
        d.presets.filter { it.kind == FleetDns.KIND_PUBLIC }.forEach { p ->
            assertEquals(listOf(StoreDns.SYSTEM), StoreDns.plan(p, listOf("Wi-Fi")).map { it.kind })
        }
    }

    @Test fun `the first resolving route wins, and a total miss names the resolvers it tried`() {
        val plan = StoreDns.plan(mirror, listOf("Wi-Fi")).map { it to Unit }
        val hit = StoreDns.pick("github.com", plan) { s, _ -> s.kind == StoreDns.UNDERLYING }
        assertEquals(StoreDns.UNDERLYING, hit!!.first.kind)
        assertNull(StoreDns.lastFailure)

        assertNull(StoreDns.pick("github.com", plan) { _, _ -> false })
        val tried = StoreDns.lastFailure!!
        assertTrue(tried, tried.startsWith("Android system resolver (${mirror.label}) → Android resolver on Wi-Fi"))
        publicServers.forEach { assertFalse("names $it, which it never asked: $tried", tried.contains(it)) }
    }

    @Test fun `only the Store's download hosts are routed`() {
        listOf("github.com", "objects.githubusercontent.com", "ghcr.io", "pkg-containers.githubusercontent.com")
            .forEach { assertTrue(it, StoreDns.isStoreHost(it)) }
        listOf("example.com", "notgithub.com", "ghcr.io.evil").forEach { assertFalse(it, StoreDns.isStoreHost(it)) }
    }
}
