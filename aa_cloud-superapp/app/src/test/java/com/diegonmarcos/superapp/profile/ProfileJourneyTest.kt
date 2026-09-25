package com.diegonmarcos.superapp.profile

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.profile.ProfileJourney.Lock
import com.diegonmarcos.superapp.profile.ProfileJourney.Phase
import com.diegonmarcos.superapp.profile.ProfileJourney.State
import com.diegonmarcos.superapp.profile.ProfileJourney.Step
import com.diegonmarcos.superapp.ui.StatusLight
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #573 — Configs ▸ Profile ▸ Connect, THE JOURNEY: the state machine and its
 * layout tree.
 *
 * The registry fixture is built from a table and every expectation is a walk
 * of that table, never a typed count. The state machine is exercised as the
 * page would drive it — nothing, a stored bearer, a session, a registry, the
 * two picks, an apply — and the layout test builds the journey the way the
 * fragment does and reads the tree back through the cockpit ids and the
 * `step:*` tags, so "four cards, in order, lights from the shared component"
 * is measured on a view, not assumed from the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileJourneyTest {

    private val identities = listOf(Triple("one@t.test", true, "personal"), Triple("two@t.test", false, "work"))
    /** id → (label, primary, profile ids) */
    private val peers = linkedMapOf(
        "p-a" to Triple("Phone A", true, "v4-full,v6-split"),
        "p-b" to Triple("Phone B", false, ""),
    )

    private fun registry(): UserRegistry.Registry {
        val root = JSONObject().put("_meta", JSONObject().put("user", "tester"))
            .put("profile", JSONObject().put("name", "Tester Person"))
        root.put("identities", JSONArray().apply {
            identities.forEach { (e, p, l) -> put(JSONObject().put("email", e).put("primary", p).put("label", l)) }
        })
        root.put("peers", JSONObject().apply {
            peers.forEach { (id, row) ->
                val wg = JSONObject()
                row.third.split(",").filter { it.isNotBlank() }.forEach { wg.put(it, JSONObject().put("name", "wg-$it").put("config_text", "x")) }
                put(id, JSONObject().put("label", row.first).put("primary", row.second).put("wireguard", wg))
            }
        })
        return UserRegistry.parse(root)!!
    }

    private val primaryEmail = identities.first { it.second }.first
    private val otherEmail = identities.first { !it.second }.first
    private val primaryPeer = peers.entries.first { it.value.second }.key
    private val otherPeer = peers.entries.first { !it.value.second }.key

    // ── the state machine ────────────────────────────────────────────────

    @Test fun `nothing known - step 1 is active and every other step is locked behind it`() {
        val s = State()
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(s, Step.SIGN_IN))
        for (step in Step.values().drop(1)) {
            assertEquals(step.name, Phase.LOCKED, ProfileJourney.phase(s, step))
            assertEquals(step.name, Lock.SIGN_IN_FIRST, ProfileJourney.lock(s, step))
        }
        assertEquals(Step.SIGN_IN, ProfileJourney.next(s))
        assertEquals(1, ProfileJourney.stepNumber(s))
        assertEquals(StatusLight.State.UNKNOWN, ProfileJourney.overall(s))
    }

    @Test fun `a stored bearer is a sign-in, but without a registry step 2 says the config was not fetched`() {
        val s = State(storedBearerEmail = primaryEmail)
        assertEquals(Phase.DONE, ProfileJourney.phase(s, Step.SIGN_IN))
        assertEquals(Lock.NO_REGISTRY, ProfileJourney.lock(s, Step.WHO))
        assertEquals(Phase.LOCKED, ProfileJourney.phase(s, Step.WHO))
        assertEquals(2, ProfileJourney.stepNumber(s))
    }

    @Test fun `an identity-only provider signs in and locks step 2 with its own reason`() {
        val s = State(session = SignIn.Session("idp", "who@t.test"), identityOnly = true)
        assertTrue(ProfileJourney.done(s, Step.SIGN_IN))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.WHO))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.DEVICE))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.GET))
    }

    @Test fun `with a registry step 2 is active and the proved address wins the default over the primary`() {
        val proved = State(session = SignIn.Session("sso", otherEmail), registry = registry())
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(proved, Step.WHO))
        assertEquals(otherEmail, ProfileJourney.defaultIdentity(proved)!!.email)
        val unproved = State(session = SignIn.Session("sso", "nobody@t.test"), registry = registry())
        assertEquals(primaryEmail, ProfileJourney.defaultIdentity(unproved)!!.email)
        val bearer = State(storedBearerEmail = otherEmail, registry = registry())
        assertEquals(otherEmail, ProfileJourney.defaultIdentity(bearer)!!.email)
        // A default is not a pick: step 2 stays undone until the owner taps a row.
        assertFalse(ProfileJourney.done(proved, Step.WHO))
        assertEquals(Lock.PICK_IDENTITY, ProfileJourney.lock(proved, Step.DEVICE))
    }

    @Test fun `the picks walk the steps in order, and the primary peer is the device default`() {
        val base = State(session = SignIn.Session("sso", primaryEmail), registry = registry(), artifactInMemory = true)
        assertEquals(primaryPeer, ProfileJourney.defaultPeer(base)!!.id)
        val who = base.copy(identity = primaryEmail)
        assertEquals(Phase.DONE, ProfileJourney.phase(who, Step.WHO))
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(who, Step.DEVICE))
        assertEquals(Lock.PICK_PEER, ProfileJourney.lock(who, Step.GET))
        val device = who.copy(peer = otherPeer)
        assertEquals(Phase.DONE, ProfileJourney.phase(device, Step.DEVICE))
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(device, Step.GET))
        assertNull(ProfileJourney.lock(device, Step.GET))
        assertEquals(4, ProfileJourney.stepNumber(device))
        val applied = device.copy(appliedAt = "2026-09-25 18:00")
        assertTrue(ProfileJourney.allDone(applied))
        assertEquals(StatusLight.State.ON, ProfileJourney.overall(applied))
        assertEquals(Phase.DONE, ProfileJourney.phase(applied, Step.GET))
    }

    @Test fun `a pick that the registry no longer knows does not count`() {
        val s = State(storedBearerEmail = primaryEmail, registry = registry(), identity = "gone@t.test", peer = "p-gone")
        assertFalse(ProfileJourney.done(s, Step.WHO))
        assertNull(s.chosenIdentity)
        assertNull(s.chosenPeer)
    }

    @Test fun `after a restart the picks hold but step 4 asks for a re-fetch, and a failed step shows red`() {
        val s = State(storedBearerEmail = primaryEmail, registry = registry(), identity = primaryEmail, peer = primaryPeer, artifactInMemory = false)
        assertEquals(Lock.REFETCH, ProfileJourney.lock(s, Step.GET))
        assertEquals(Phase.LOCKED, ProfileJourney.phase(s, Step.GET))
        val failed = s.copy(artifactInMemory = true, failed = setOf(Step.GET))
        assertEquals(Phase.FAILED, ProfileJourney.phase(failed, Step.GET))
        assertEquals(StatusLight.State.OFF, ProfileJourney.light(Phase.FAILED))
        assertTrue(ProfileJourney.bodyOpen(Phase.FAILED))
    }

    @Test fun `every phase maps to a distinct shared light and only active or failed bodies are open`() {
        val lights = Phase.values().map { ProfileJourney.light(it) }
        assertEquals(Phase.values().size, lights.toSet().size)
        assertEquals(StatusLight.State.ON, ProfileJourney.light(Phase.DONE))
        assertEquals(StatusLight.State.UNVERIFIABLE, ProfileJourney.light(Phase.LOCKED))
        assertEquals(setOf(Phase.ACTIVE, Phase.FAILED), Phase.values().filter { ProfileJourney.bodyOpen(it) }.toSet())
    }

    // ── the layout tree ──────────────────────────────────────────────────

    private val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Superapp)

    private fun build(): ProfileJourneyView.Journey = ProfileJourneyView.build(
        ctx, "Tester Person", "sub", R.drawable.ic_link_tile,
        Step.values().associateWith { "${it.ordinal + 1} · ${it.name}" },
        Step.values().associateWith { R.drawable.ic_link_tile },
        "toggle",
    )

    private fun cards(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.id == R.id.cockpit_card) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root); return out
    }

    @Test fun `the journey is a hero then exactly four cards, in step order, tagged step colon name`() {
        val j = build()
        val hero = j.root.findViewById<View>(R.id.cockpit_hero)
        assertTrue("the hero is the first child", j.root.getChildAt(0) === hero)
        val found = cards(j.root)
        assertEquals(Step.values().size, found.size)
        assertEquals(Step.values().map { ProfileJourney.tag(it) }, found.map { it.tag })
        assertTrue("the rail sits in the hero's slot", j.hero.slot.getChildAt(0) === j.rail)
    }

    @Test fun `paint puts the shared light on every card and opens only the active step's body`() {
        val j = build()
        val s = State(storedBearerEmail = primaryEmail, registry = registry())   // step 2 active
        ProfileJourneyView.paint(j, s, Step.values().associateWith { "summary ${it.name}" }, "Step 2 of 4")
        for (step in Step.values()) {
            val card = j.cards.getValue(step)
            val phase = ProfileJourney.phase(s, step)
            val light = ProfileJourney.light(phase)
            assertEquals(step.name, StatusLight.text(ctx, light), card.light.text.toString())
            assertEquals(step.name, StatusLight.colour(ctx, light), card.light.currentTextColor)
            assertEquals(step.name, if (ProfileJourney.bodyOpen(phase)) View.VISIBLE else View.GONE, card.body.visibility)
            assertEquals("summary ${step.name}", card.summary.text.toString())
        }
        assertEquals("Step 2 of 4", j.hero.summary.text.toString())
        assertEquals(StatusLight.text(ctx, StatusLight.State.UNKNOWN), j.hero.light.text.toString())
        // The rail names every step with its glyph, in order.
        val rail = j.rail.text.toString()
        var last = -1
        for (step in Step.values()) {
            val i = rail.indexOf(j.cards.getValue(step).label)
            assertTrue("rail names ${step.name}", i > last); last = i
        }
        assertTrue(rail.contains(StatusLight.glyph(StatusLight.State.ON)))
        assertTrue(rail.contains(StatusLight.glyph(StatusLight.State.UNVERIFIABLE)))
    }

    @Test fun `a choice row carries its state as a glyph, not only as ink`() {
        val on = ProfileJourneyView.choice(ctx, "row", true) {}
        val off = ProfileJourneyView.choice(ctx, "row", false) {}
        assertTrue(on.text.startsWith("●"))
        assertTrue(off.text.startsWith("○"))
        assertEquals("row", on.contentDescription)
        assertTrue(on.isClickable && off.isClickable)
    }
}
