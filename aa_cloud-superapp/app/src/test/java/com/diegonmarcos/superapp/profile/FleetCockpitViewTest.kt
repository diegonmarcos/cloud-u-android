package com.diegonmarcos.superapp.profile

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.StatusLight
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #570 reopened — the Fleet cockpit's LAYOUT TREE.
 *
 * The first #570 delivery went green on eleven JVM tests and seventy-one shell
 * checks while the page looked exactly as before, because nothing measured
 * what was on screen. This does. It builds the cockpit the way the fragment
 * does — from the baked cockpit declaration, under the app's theme — and reads
 * the tree back through the ids in res/values/ids.xml:
 *
 *  • a hero with a round orb, and one card per declared section, in order,
 *    each tagged with its section id;
 *  • every card's light is the SHARED [StatusLight] — its glyph+word and its
 *    colour for the state it was painted, and a spoken description that names
 *    the section;
 *  • every badge is an OVAL (the homescreen's circle-icon language, not a square);
 *  • the header toggles the body, which starts visible so no Apply is hidden;
 *  • and the OLD idiom is gone: a section label appears only INSIDE its card,
 *    never as a bare headline on the page — which is what the previous page was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetCockpitViewTest {

    private val ctx = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Superapp)

    /** The fragment's own order of states across the six cards, so every shared state is painted at least once. */
    private val states = listOf(
        StatusLight.State.ON, StatusLight.State.UNVERIFIABLE, StatusLight.State.OFF,
        StatusLight.State.UNKNOWN, StatusLight.State.ON, StatusLight.State.OFF,
    )

    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun build(layout: VaultCockpit.Layout): Pair<LinearLayout, List<FleetCockpitView.Card>> {
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hero = FleetCockpitView.hero(ctx, "termux-galaxy", "mesh 10.0.0.9 · fd0c:1d00::9",
            Sections.iconResFor(ctx, VaultCockpit.deviceIcon(layout, null)))
        FleetCockpitView.paint(hero.light, StatusLight.State.OFF, "termux-galaxy")
        page.addView(hero.root)
        val cards = layout.sections.mapIndexed { i, s ->
            FleetCockpitView.card(ctx, s.label, s.id, Sections.iconResFor(ctx, s.icon), "toggle").also {
                FleetCockpitView.paint(it.light, states[i % states.size], s.label)
                page.addView(it.root)
            }
        }
        return page to cards
    }

    @Test fun `the baked cockpit is a hero and one tagged card per declared section, in order`() {
        val layout = VaultCockpit.layout
        assertTrue("the baked layout must declare sections", layout.sections.isNotEmpty())
        val (page, _) = build(layout)
        val hero = page.findViewById<View>(R.id.cockpit_hero)
        assertNotNull("no hero on the page", hero)
        assertEquals("the hero is the first thing on the page", hero, page.getChildAt(0))
        val orb = page.findViewById<View>(R.id.cockpit_device_orb)
        assertTrue("the hero orb is not a circle", (orb.background as? GradientDrawable)?.shape == GradientDrawable.OVAL)
        assertNotNull(page.findViewById<TextView>(R.id.cockpit_hero_light))

        val cards = all(page).filter { it.id == R.id.cockpit_card }
        assertEquals(layout.sections.map { it.id }, cards.map { it.tag })
        assertEquals("the cards follow the hero, in declared order",
            listOf(hero) + cards, (0 until page.childCount).map { page.getChildAt(it) })
    }

    @Test fun `every card light is the shared StatusLight for the state it was painted`() {
        val (page, cards) = build(VaultCockpit.layout)
        cards.forEachIndexed { i, card ->
            val state = states[i % states.size]
            val light = card.root.findViewById<TextView>(R.id.cockpit_card_light)
            assertEquals(StatusLight.text(ctx, state), light.text.toString())
            assertEquals("card ${card.tag} colour", StatusLight.colour(ctx, state), light.currentTextColor)
            assertEquals(StatusLight.description(ctx, card.label, state), light.contentDescription.toString())
            val badge = card.root.findViewById<View>(R.id.cockpit_card_badge)
            assertTrue("${card.tag}'s badge is not a circle", (badge.background as? GradientDrawable)?.shape == GradientDrawable.OVAL)
        }
        // The four shared states, and only those, are what a cockpit light can say.
        val painted = all(page).filter { it.id == R.id.cockpit_card_light }.map { (it as TextView).text.toString() }
        val vocabulary = StatusLight.State.values().map { StatusLight.text(ctx, it) }.toSet()
        assertTrue("a light says something StatusLight does not: $painted", painted.all { it in vocabulary })
    }

    @Test fun `the header toggles the body, which starts visible so no Apply is hidden`() {
        val (_, cards) = build(VaultCockpit.layout)
        val card = cards.first()
        val body = card.root.findViewById<View>(R.id.cockpit_card_body)
        assertEquals(View.VISIBLE, body.visibility)
        val header = card.root.getChildAt(0)
        assertTrue("the header is the tap target", header.isClickable)
        header.performClick()
        assertEquals(View.GONE, body.visibility)
        header.performClick()
        assertEquals(View.VISIBLE, body.visibility)
        assertEquals("the body is what the fragment renders into", body, card.body)
    }

    @Test fun `the old page is gone - a section label lives only inside its card, never as a bare headline`() {
        val layout = VaultCockpit.parseLayout(JSONObject(
            """{"sections":[{"id":"mail","label":"Mail","vault":["mail"],"icon":"ic_mail"},
                            {"id":"apps","label":"Apps","vault":["apps"],"icon":"ic_home_apps"}]}"""))
        val (page, cards) = build(layout)
        val labels = layout.sections.map { it.label }
        val labelViews = all(page).filterIsInstance<TextView>().filter { it.text.toString() in labels }
        assertEquals("one label view per section", labels.size, labelViews.size)
        for (tv in labelViews) {
            var p = tv.parent
            var inCard = false
            while (p is View) { if (p.id == R.id.cockpit_card) inCard = true; p = p.parent }
            assertTrue("'${tv.text}' is drawn outside a cockpit card — the old headline idiom", inCard)
            // A label is not a light: the light is the one that carries the shared vocabulary.
            assertTrue(tv.id != R.id.cockpit_card_light)
        }
        // And the card whose label it is: tag and label agree.
        cards.forEach { c -> assertEquals(layout.sections.first { it.id == c.tag }.label, c.label) }
    }

    @Test fun `no colour is a literal - fills come from the palette and lights from StatusLight`() {
        // The lights are the point: the same state painted on two lights is the same colour,
        // and the hero's light and a card's light for the same state cannot differ.
        val (page, cards) = build(VaultCockpit.layout)
        val heroLight = page.findViewById<TextView>(R.id.cockpit_hero_light)
        FleetCockpitView.paint(heroLight, StatusLight.State.ON, "x")
        FleetCockpitView.paint(cards.first().light, StatusLight.State.ON, "y")
        assertEquals(heroLight.currentTextColor, cards.first().light.currentTextColor)
        assertEquals(StatusLight.colour(ctx, StatusLight.State.ON), heroLight.currentTextColor)
    }
}
