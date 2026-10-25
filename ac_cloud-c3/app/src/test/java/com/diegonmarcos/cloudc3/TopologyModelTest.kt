package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.TopologyModel.Deployed
import com.diegonmarcos.cloudc3.pages.TopologyModel.Health
import com.diegonmarcos.cloudc3.pages.TopologyModel.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping per topology view, from FIXTURE fleet data that exists in this file only (obviously fake names; the shipped
 * declarations are never restated here). The shape mirrors the real documents: containers-declared.json, fleet_slice.json,
 * infra_slice.json and the `docker ps` answer of GET /health/deployed/{vm}.
 */
class TopologyModelTest {

    private val inputs = TopologyFixtures.inputs

    private fun find(nodes: List<Node>, label: String): Node =
        TopologyModel.walk(nodes).map { it.first }.firstOrNull { it.label == label } ?: throw AssertionError("no node '$label'")

    private fun labels(n: Node) = n.children.map { it.label }

    @Test
    fun `service view groups gateway then service then its container and DB, worst first`() {
        val tree = TopologyModel.build(TopologyModel.SERVICE, inputs)
        assertEquals(listOf("Gateway: public edge", "Mesh only: behind the gateway, no public route"), tree.map { it.label })
        val web = tree[0].children.first { it.label == "web" }
        assertEquals("a DB the service depends on sits under the service, the broken one first", listOf("web-db (postgres)", "web"), labels(web))
        assertEquals("dependency", web.children[0].kind)
        assertEquals("the service is as bad as its worst part", Health.BAD, web.health)
        assertTrue("the edge carries the auth tier", web.metric.contains("auth two_factor"))
        assertEquals(listOf("api", "orphan"), labels(tree[1]).sorted())
    }

    @Test
    fun `infrastructure view groups provider then VM then container and the self-hosted Cloud is not a second tree`() {
        val tree = TopologyModel.build(TopologyModel.INFRASTRUCTURE, inputs)
        assertEquals(listOf("Not on a mesh VM", "Provider One"), tree.map { it.label }.sorted())
        assertTrue("no Cloud and no empty vendor in the compute tree", tree.none { it.label == "Cloud" || it.label == "Empty Vendor" })
        val pv = tree.first { it.label == "Provider One" }
        assertEquals(listOf("vm-a", "vm-b"), labels(pv).sorted())
        val a = pv.children.first { it.label == "vm-a" }
        assertTrue("disk usage rides the VM", a.metric.contains("disk 77%"))
        assertEquals(listOf("web", "web-db"), labels(a).sorted())
        assertTrue("an unread VM says so instead of drawing 0%", pv.children.first { it.label == "vm-b" }.metric.contains("disk not read"))
        assertEquals(listOf("orphan"), labels(tree.first { it.label == "Not on a mesh VM" }))
    }

    @Test
    fun `orchestration view groups fleet then VM then compose project then service then container with declared vs deployed`() {
        val root = TopologyModel.build(TopologyModel.ORCHESTRATION, inputs).first()
        assertEquals("Cloud", root.label)
        val a = root.children.first { it.label == "vm-a" }
        assertEquals(listOf("proj1", "Running but not declared"), labels(a).sortedByDescending { it == "proj1" })
        val web = find(listOf(a), "proj1").children.single()
        assertEquals("web", web.label)
        assertTrue(web.children.first { it.label == "web" }.metric.startsWith("in sync"))
        assertTrue(web.children.first { it.label == "web-db" }.metric.startsWith("drift"))
        assertEquals("a running container nobody declared is flagged", Health.WARN, find(listOf(a), "stray").health)
        assertEquals("a VM that could not be read is unknown, not healthy", Health.UNKNOWN, find(listOf(root), "api").health)
    }

    @Test
    fun `network view groups subnet then VM then routes and firewall, routes lead to container ip and port`() {
        val tree = TopologyModel.build(TopologyModel.NETWORK, inputs)
        assertEquals(listOf("wg0 10.0.0.0/24", "DNS (mesh namespace, primary 10.0.0.1)"), tree.map { it.label })
        val a = find(tree, "vm-a")
        assertEquals(listOf("Caddy routes (1)", "Firewall (1 rules)"), labels(a))
        val route = find(listOf(a), "w.test")
        assertTrue(route.metric.contains("two_factor"))
        assertEquals("10.0.0.2:8080", route.children.single().metric)
        assertEquals("a client peer is a leaf", emptyList<Node>(), find(tree, "phone").children)
        assertEquals("443/tcp from 0.0.0.0/0", find(listOf(a), "443/tcp from 0.0.0.0/0").label)
    }

    @Test
    fun `release view lists the last Ship runs then VM, service and declared against running version`() {
        val tree = TopologyModel.build(TopologyModel.RELEASE, inputs)
        assertEquals("Last Ship runs (r)", tree[0].label)
        assertEquals("runs keep GitHub's newest-first order", listOf("Ship ok", "Ship bad"), labels(tree[0]))
        assertEquals(Health.OK, find(tree, "Ship ok").health)
        assertEquals(Health.BAD, find(tree, "Ship bad").health)
        val web = find(tree, "web").children.first { it.label == "web" }
        assertEquals("declared 1.2, running 1.3", web.metric)
        assertEquals("a version that differs from the declaration is flagged", Health.WARN, web.health)
    }

    @Test
    fun `a pivot opens the same entity in the other four views and keeps only its branches`() {
        val orch = TopologyModel.build(TopologyModel.ORCHESTRATION, inputs)
        val web = find(orch, "web")
        assertEquals(TopologyModel.Keys.service("web"), web.pivot)
        assertEquals(listOf("service", "infrastructure", "network", "release"), TopologyModel.pivots(web, TopologyModel.ORCHESTRATION))
        assertEquals("a group with no entity has no pivot", emptyList<String>(), TopologyModel.pivots(orch[0], TopologyModel.ORCHESTRATION))
        for (view in TopologyModel.ALL) {
            val focused = TopologyModel.focus(TopologyModel.build(view, inputs), web.pivot)
            assertTrue("$view shows web when opened on it", TopologyModel.walk(focused).any { it.first.keys.contains(web.pivot) })
            assertTrue("$view drops what is not web", TopologyModel.walk(focused).none { it.first.label == "orphan" })
        }
        val vm = TopologyModel.build(TopologyModel.INFRASTRUCTURE, inputs).first { it.label == "Provider One" }.children.first { it.label == "vm-b" }
        val onVmB = TopologyModel.focus(TopologyModel.build(TopologyModel.SERVICE, inputs), vm.pivot)
        assertEquals("opened on a VM, the service view keeps its services", listOf("api"), TopologyModel.walk(onVmB).map { it.first }.filter { it.kind == "service" }.map { it.label })
    }

    @Test
    fun `the list fallback is every leaf with its path`() {
        val rows = TopologyModel.flatten(TopologyModel.build(TopologyModel.INFRASTRUCTURE, inputs))
        val web = rows.first { it.node.label == "web" }
        assertEquals("Provider One > vm-a", web.path)
        assertEquals(inputs.catalog.entries.size, rows.size)
    }

    @Test
    fun `image tags read as tag, digest pin or latest`() {
        assertEquals("1.2", TopologyModel.imageTag("repo/web:1.2"))
        assertEquals("8.10.2-alpine", TopologyModel.imageTag("redis:8.10.2-alpine@sha256:3811787313eb"))
        assertEquals("38117873", TopologyModel.imageTag("repo/x@sha256:3811787313eb"))
        assertEquals("latest", TopologyModel.imageTag("host:5000/repo/x"))
    }

    @Test
    fun `a deployed answer for another VM or with an error is blind, never an empty list`() {
        assertTrue(TopologyModel.parseDeployed("id-z", TopologyFixtures.ps) is Deployed.Blind)
        assertTrue(TopologyModel.parseDeployed("id-a", """[{"vm":"id-a","error":"ssh timeout"}]""") is Deployed.Blind)
        assertTrue(TopologyModel.parseDeployed("id-a", "not json") is Deployed.Blind)
        assertNotNull((TopologyModel.parseDeployed("id-a", TopologyFixtures.ps) as Deployed.Seen).boxes.firstOrNull { it.name == "web" })
    }

    @Test
    fun `without a live read every container is unknown, not healthy`() {
        val dry = inputs.copy(live = TopologyModel.Live())
        val leaves = TopologyModel.flatten(TopologyModel.build(TopologyModel.SERVICE, dry)).map { it.node.health }
        assertTrue(leaves.all { it == Health.UNKNOWN })
    }
}
