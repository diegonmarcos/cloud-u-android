package app.sterna.core.data.account

import app.sterna.core.jmap.EndpointPolicy
import app.sterna.core.jmap.EndpointRouter
import app.sterna.core.jmap.FleetBearers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * #1090 What Mail does with what Cloud Account published: a fleet account (published, and on the published
 * public host) gets both endpoints and the bearer wired onto the transport; every other account gets nothing and
 * keeps its single endpoint; the owner's policy survives a re-sync; the bearer is stored only sealed and never
 * printed.
 */
class FleetEndpointWiringTest {
    private val token = "fleet-SECRET-tok-123"
    private val pub = "https://jmap.example.org"
    private val mesh = "https://stalwart.app:2443"

    private class Disk : FleetEndpointStore.Blobs {
        val map = linkedMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val disk = Disk()
    // A reversible stand-in for the Keystore: the stored text is not the plaintext.
    private fun seal(id: String, plain: String) = Base64.getEncoder().encodeToString((id + "|" + plain).toByteArray().reversedArray())
    private fun open(id: String, raw: String): String? = String(Base64.getDecoder().decode(raw).reversedArray()).removePrefix("$id|")

    private fun store(d: Disk = disk) = FleetEndpointStore(d, ::seal, ::open)
    private class Rig(val store: FleetEndpointStore, val router: EndpointRouter, val bearers: FleetBearers, val wiring: FleetEndpointWiring)
    private fun rig(d: Disk = disk): Rig {
        val s = store(d); val r = EndpointRouter(probe = { _, _, _ -> true }); val b = FleetBearers()
        return Rig(s, r, b, FleetEndpointWiring(s, r, b))
    }

    private fun acc(id: String, user: String, server: String = pub) = StoredAccount(id = id, server = server, username = user)
    private fun published(user: String, policy: EndpointPolicy = EndpointPolicy.MESH_FIRST) = PublishedEndpoint(user, mesh, pub, policy)

    @Test fun `a fleet account gets both endpoints and the bearer on the public host`() {
        val r = rig()
        val rep = r.wiring.applySync(listOf(acc("a", "me@example.org")), listOf(published("me@example.org")), token, 5L)
        assertEquals(1, rep.applied); assertTrue(rep.bearerSynced)
        val e = r.store.get("a")!!
        assertEquals(mesh, e.mesh); assertEquals(pub, e.public); assertEquals(EndpointPolicy.MESH_FIRST, e.policy)
        assertTrue(r.router.isRegistered("jmap.example.org"))
        assertEquals(token, r.bearers.forHost("jmap.example.org"))
        assertNull("never on the mesh host", r.bearers.forHost("stalwart.app"))
    }

    @Test fun `an account Cloud Account did not publish keeps its single endpoint and gets no token`() {
        val r = rig()
        r.wiring.applySync(listOf(acc("a", "me@example.org"), acc("g", "me@gmail.example", "imap.gmail.example")), listOf(published("me@example.org")), token, 5L)
        assertNull(r.store.get("g"))
        assertFalse(r.router.isRegistered("imap.gmail.example"))
        assertNull(r.bearers.forHost("imap.gmail.example"))
    }

    @Test fun `a published address pointed at another server is not the fleet's - no route, no token`() {
        val r = rig()
        val rep = r.wiring.applySync(listOf(acc("a", "me@example.org", "https://mail.other.example")), listOf(published("me@example.org")), token, 5L)
        assertEquals(0, rep.applied); assertEquals(listOf("me@example.org"), rep.skipped)
        assertNull(r.store.get("a")); assertFalse(r.router.isRegistered("jmap.example.org")); assertNull(r.bearers.forHost("mail.other.example"))
    }

    @Test fun `the address and the host match in any case and spelling`() {
        val r = rig()
        r.wiring.applySync(listOf(acc("a", "Me@Example.org", "JMAP.example.org")), listOf(published("me@example.org")), token, 5L)
        assertNotNull(r.store.get("a")); assertTrue(r.router.isRegistered("jmap.example.org"))
    }

    @Test fun `the owner's policy is kept across a re-sync, an unpinned one follows Account`() {
        val r = rig(); val accounts = listOf(acc("a", "me@example.org"))
        r.wiring.applySync(accounts, listOf(published("me@example.org")), token, 1L)
        r.wiring.applySync(accounts, listOf(published("me@example.org", EndpointPolicy.PUBLIC_ONLY)), token, 2L)
        assertEquals("not pinned: Account's new default wins", EndpointPolicy.PUBLIC_ONLY, r.store.get("a")!!.policy)
        r.wiring.setPolicy(accounts, "a", EndpointPolicy.MESH_ONLY)
        r.wiring.applySync(accounts, listOf(published("me@example.org", EndpointPolicy.MESH_FIRST)), token, 3L)
        assertEquals("pinned: the owner's choice stands", EndpointPolicy.MESH_ONLY, r.store.get("a")!!.policy)
        assertEquals(EndpointPolicy.MESH_ONLY, r.router.policyOf("jmap.example.org"))
    }

    @Test fun `one endpoint, one policy - accounts on the same host move together`() {
        val r = rig(); val accounts = listOf(acc("a", "me@example.org"), acc("b", "bot@example.org"))
        r.wiring.applySync(accounts, listOf(published("me@example.org"), published("bot@example.org")), token, 1L)
        r.wiring.setPolicy(accounts, "a", EndpointPolicy.PUBLIC_ONLY)
        assertEquals(EndpointPolicy.PUBLIC_ONLY, r.store.get("b")!!.policy)
        assertTrue(r.store.get("b")!!.policyPinned)
    }

    @Test fun `a bearer Account stops sharing is taken off the wire`() {
        val r = rig(); val accounts = listOf(acc("a", "me@example.org"))
        r.wiring.applySync(accounts, listOf(published("me@example.org")), token, 1L)
        val rep = r.wiring.applySync(accounts, listOf(published("me@example.org")), "", 2L)
        assertFalse(rep.bearerSynced); assertNull(r.bearers.forHost("jmap.example.org")); assertEquals("", r.store.get("a")!!.bearer)
    }

    @Test fun `an account that stops being published is deregistered`() {
        val r = rig(); val accounts = listOf(acc("a", "me@example.org"))
        r.wiring.applySync(accounts, listOf(published("me@example.org")), token, 1L)
        r.wiring.applySync(accounts, emptyList(), token, 2L)
        assertNull(r.store.get("a")); assertFalse(r.router.isRegistered("jmap.example.org")); assertNull(r.bearers.forHost("jmap.example.org"))
    }

    @Test fun `after a restart the stored endpoints go back on the transport without asking Account`() {
        rig().wiring.applySync(listOf(acc("a", "me@example.org")), listOf(published("me@example.org")), token, 1L)
        val again = rig()
        assertFalse(again.router.isRegistered("jmap.example.org"))
        again.wiring.restore(listOf(acc("a", "me@example.org")))
        assertTrue(again.router.isRegistered("jmap.example.org")); assertEquals(token, again.bearers.forHost("jmap.example.org"))
    }

    @Test fun `deleting an account drops its endpoint and, when it was the last on the host, the route`() {
        val r = rig(); val accounts = listOf(acc("a", "me@example.org"))
        r.wiring.applySync(accounts, listOf(published("me@example.org")), token, 1L)
        r.wiring.forget(accounts, "a")
        assertNull(r.store.get("a")); assertFalse(r.router.isRegistered("jmap.example.org")); assertNull(r.bearers.forHost("jmap.example.org"))
    }

    @Test fun `the bearer is stored sealed and never printed`() {
        val r = rig()
        val rep = r.wiring.applySync(listOf(acc("a", "me@example.org")), listOf(published("me@example.org")), token, 1L)
        assertTrue("sealed at rest", disk.map.values.none { it.contains(token) })
        assertFalse(r.store.get("a")!!.toString().contains(token))
        assertFalse(rep.toString().contains(token))
        assertFalse(r.bearers.toString().contains(token))
        val src = File("src/main/kotlin/app/sterna/core/data/account/FleetEndpointStore.kt").readText()
        assertFalse(Regex("""(Log\.|println\(|Timber|printStackTrace)""").containsMatchIn(src))
    }
}
