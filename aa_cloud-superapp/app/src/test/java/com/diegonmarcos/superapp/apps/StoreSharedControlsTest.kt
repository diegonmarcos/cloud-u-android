package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppsMesh
import com.diegonmarcos.superapp.appstore.AppsMeshFragment
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StoreControls
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #793 the Store ▸ Apps Mesh page draws its actions and filters with the SAME
 * component the Store's main page uses — not a look-alike. Asserted on what the
 * two pages actually RENDER: Store ▸ Cloud (its bar, an app row, its filter
 * chips), then its Apps Mesh tab opened by tapping it, then the same page
 * hosted by Configs (AppsMeshFragment).
 *
 * "Same component" is checked two ways that can each fail on their own: the
 * view is a [StoreControls.Button] / [StoreControls.Chip] (a TextView painted
 * to look alike is not), and it measures the same — text size, weight,
 * padding, corner radius — as the Store's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreSharedControlsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64
    private val fleet = Fleet.parse(b64)
    private val decl = AppsMesh.load(ctx)
    private val meshLabel = JSONObject(ctx.assets.open(StoreControls.ASSET).use { it.readBytes().decodeToString() })
        .getJSONObject("pages").getJSONObject("mesh").getString("label")

    private fun host(f: Fragment): View {
        val act = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        act.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        return f.requireView()
    }

    private fun views(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun tagged(root: View, prefix: String) = views(root).filter { (it.tag as? String)?.startsWith(prefix) == true }

    /** What makes two controls look the same: their class and their measures. */
    private fun look(v: View): String {
        val t = v as TextView
        val lp = t.layoutParams as LinearLayout.LayoutParams
        val bg = t.background as GradientDrawable
        return "${v.javaClass.simpleName} ${t.textSize} w${lp.weight} p${t.paddingLeft},${t.paddingTop} r${bg.cornerRadius}"
    }

    @Test
    fun `the Store and its Apps Mesh page render the same button and chip components`() {
        val store = StoreCloudFragment()
        val root = host(store)

        // ── the Store's main page: its bar, an app row, its filter ──
        val bar = root.findViewWithTag<ViewGroup>(StoreBar.TAG)
        assertNotNull("Store ▸ Cloud draws no bar", bar)
        val barButtons = views(bar!!).filter { it.tag is StoreBar.Item }
        assertTrue("the bar drew no buttons", barButtons.size >= 4)
        assertEquals("a Store bar control is not the shared button: ${barButtons.filter { it !is StoreControls.Button }.map { it.tag }}",
            emptyList<Any>(), barButtons.filter { it !is StoreControls.Button }.map { it.tag })
        val rowButtons = views(root).filter { it is TextView && it.text.toString() == "Details" }
        assertTrue("no app row drew its Details button", rowButtons.isNotEmpty())
        assertTrue("an app row's button is not the shared button", rowButtons.all { it is StoreControls.Button })
        val storeChips = views(root).filterIsInstance<StoreControls.Chip>()
        assertEquals("the Store's filter is not four shared chips", 4, storeChips.size)
        val storeButtonLook = look(barButtons.first())
        val storeChipLook = look(storeChips.first())

        // ── Store ▸ Apps Mesh, opened the way a user opens it ──
        val tab = views(root).filterIsInstance<TextView>().firstOrNull { it.text.toString().contains(meshLabel) }
        assertNotNull("no '$meshLabel' tab on Store ▸ Cloud", tab)
        tab!!.performClick()
        assertMeshPage(root, hasStore = true, storeButtonLook, storeChipLook)

        // ── the same page hosted by Configs ▸ Watchdog ▸ Mesh ──
        assertMeshPage(host(AppsMeshFragment()), hasStore = false, storeButtonLook, storeChipLook)
    }

    private fun assertMeshPage(root: View, hasStore: Boolean, buttonLook: String, chipLook: String) {
        val where = if (hasStore) "Store ▸ Apps Mesh" else "Configs ▸ Apps Mesh"
        // page tools: every laid-out tool, each the shared button, looking like the Store's
        val tools = tagged(root, AppsMesh.TAG_TOOL)
        assertEquals("$where tools", decl.toolRows.flatten().sorted(), tools.map { (it.tag as String).removePrefix(AppsMesh.TAG_TOOL) }.sorted())
        for (t in tools) {
            assertTrue("$where tool ${t.tag} is not the shared button", t is StoreControls.Button)
            assertEquals("$where tool ${t.tag} does not look like the Store's buttons",
                buttonLook.substringBefore(" r"), look(t).substringBefore(" r"))
            assertEquals(buttonLook.substringAfter(" r"), look(t).substringAfter(" r"))
        }
        // each member's row of buttons, like a Store app row's
        val actions = AppsMesh.actionsFor(decl, hasStore).map { it.id }
        val rowButtons = tagged(root, AppsMesh.TAG_ACTION)
        for (app in fleet) for (a in actions)
            assertTrue("$where: ${app.id} has no '$a' button",
                rowButtons.any { it.tag == "${AppsMesh.TAG_ACTION}$a:${app.id}" })
        assertTrue("$where: a member's row button is not the shared button", rowButtons.all { it is StoreControls.Button })
        assertEquals("$where: Store row offered where there is no Store", hasStore, rowButtons.any { (it.tag as String).startsWith("${AppsMesh.TAG_ACTION}store:") })
        assertEquals(buttonLook, look(rowButtons.first()))
        // filter chips: the declared five, the shared chip, with a count, looking like the Store's filter
        val chips = tagged(root, AppsMesh.TAG_FILTER)
        assertEquals("$where chips", AppsMesh.FILTERS, chips.map { (it.tag as String).removePrefix(AppsMesh.TAG_FILTER) })
        assertTrue("$where: a filter chip is not the shared chip", chips.all { it is StoreControls.Chip })
        assertTrue("$where: a chip carries no count", chips.all { Regex("\\(\\d+\\)$").containsMatchIn((it as TextView).text) })
        assertEquals(chipLook, look(chips.first()))
        // static first: every member's card is on the page before any probe answered
        val cards = tagged(root, StoreMesh.TAG_NODE).map { (it.tag as String).removePrefix(StoreMesh.TAG_NODE) }
        assertEquals("$where: not every member is drawn up front", fleet.map { it.id }.sorted(), cards.sorted())
        assertFalse("$where: no probe-age line", tagged(root, AppsMesh.TAG_AGE).isEmpty())
    }

    @Test
    fun `control - a TextView painted like the button is not the shared component`() {
        // the class check above discriminates: a look-alike fails it
        val style = StoreControls.load(ctx).action
        val real = StoreControls.button(ctx, style, "x", 0, {})
        val fake = TextView(ctx).apply { background = real.background; textSize = 12f }
        assertTrue(real is StoreControls.Button)
        assertFalse(fake is StoreControls.Button)
        // and a disabled verb is still the shared button, drawn dimmed
        val off = StoreControls.button(ctx, style, "x", 0, null)
        assertFalse(off.isEnabled); assertTrue(off.alpha < 1f)
        // Base64 of the baked fleet is what both pages draw from
        assertTrue(String(Base64.decode(b64, Base64.DEFAULT)).contains("\"apps\""))
    }
}
