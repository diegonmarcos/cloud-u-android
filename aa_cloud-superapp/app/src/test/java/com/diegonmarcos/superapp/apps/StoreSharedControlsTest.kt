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
import org.junit.Assert.assertNull
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
 * view carries the mark only [StoreBar.button] / [StoreBar.chip] set (a
 * TextView painted to look alike does not), and it measures the same — text
 * size, weight, padding, corner radius — as the Store's own.
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

    /** What makes two controls look the same: their mark and their measures. */
    private fun look(v: View): String {
        val t = v as TextView
        val lp = t.layoutParams as LinearLayout.LayoutParams
        val bg = t.background as GradientDrawable
        return "${if (StoreBar.isButton(v)) "button" else if (StoreBar.isChip(v)) "chip" else "plain"} ${t.textSize} w${lp.weight} p${t.paddingLeft},${t.paddingTop} r${bg.cornerRadius}"
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
        assertEquals("a Store bar control is not the shared button: ${barButtons.filterNot { StoreBar.isButton(it) }.map { it.tag }}",
            emptyList<Any>(), barButtons.filterNot { StoreBar.isButton(it) }.map { it.tag })
        val rowButtons = views(root).filter { it is TextView && it.text.toString() == "Details" }
        assertTrue("no app row drew its Details button", rowButtons.isNotEmpty())
        assertTrue("an app row's button is not the shared button", rowButtons.all { StoreBar.isButton(it) })
        val storeChips = views(root).filter { StoreBar.isChip(it) }
        assertEquals("the Store's filter is not four shared chips", 4, storeChips.size)
        val storeButtonLook = look(barButtons.first())
        val storeChipLook = look(storeChips.first())

        // ── Store ▸ Apps Mesh, opened the way a user opens it ──
        val tab = views(root).filterIsInstance<TextView>().firstOrNull { it.text.toString().contains(meshLabel) }
        assertNotNull("no '$meshLabel' tab on Store ▸ Cloud", tab)
        tab!!.performClick()
        assertMeshPage(root, hasStore = true, storeButtonLook, storeChipLook)

        // ── the same page hosted by Configs ▸ Setup ▸ Network ──
        assertMeshPage(host(AppsMeshFragment()), hasStore = false, storeButtonLook, storeChipLook)
    }

    private fun assertMeshPage(root: View, hasStore: Boolean, buttonLook: String, chipLook: String) {
        val where = if (hasStore) "Store ▸ Apps Mesh" else "Configs ▸ Apps Mesh"
        // #809 root controls: each declared page / action in the group of its declared type, never another
        assertControlsGrouped(root, "root", where, buttonLook)
        // each member's row of buttons, like a Store app row's
        val actions = AppsMesh.actionsFor(decl, hasStore).map { it.id }
        val rowButtons = tagged(root, AppsMesh.TAG_ACTION)
        for (app in fleet) for (a in actions)
            assertTrue("$where: ${app.id} has no '$a' button",
                rowButtons.any { it.tag == "${AppsMesh.TAG_ACTION}$a:${app.id}" })
        for (b in rowButtons) {
            val id = (b.tag as String).removePrefix(AppsMesh.TAG_ACTION).substringBefore(':')
            val type = decl.of("member").first { it.id == id }.type
            assertTrue("$where: member control $id ($type) is not the shared $type control",
                if (type == "page") StoreBar.isPage(b) else StoreBar.isButton(b))
            assertEquals("$where: member control $id sits outside its $type group",
                "${AppsMesh.TAG_GROUP}member:$type", (b.parent as View).tag)
        }
        assertEquals("$where: Store row offered where there is no Store", hasStore, rowButtons.any { (it.tag as String).startsWith("${AppsMesh.TAG_ACTION}store:") })
        assertEquals(buttonLook, look(rowButtons.first { StoreBar.isButton(it) }))
        // filter chips: the declared five, the shared chip, with a count, looking like the Store's filter
        val chips = tagged(root, AppsMesh.TAG_FILTER)
        assertEquals("$where chips", AppsMesh.FILTERS, chips.map { (it.tag as String).removePrefix(AppsMesh.TAG_FILTER) })
        assertTrue("$where: a filter chip is not the shared chip", chips.all { StoreBar.isChip(it) })
        assertTrue("$where: a chip carries no count", chips.all { Regex("\\(\\d+\\)$").containsMatchIn((it as TextView).text) })
        assertEquals(chipLook, look(chips.first()))
        assertTrue("$where: a filter chip sits outside the filter group",
            chips.all { ((it.parent as View).parent as View).tag == "${AppsMesh.TAG_GROUP}root:filter" })
        // #809 Export lives on the endpoints page, not on root; opening it shows its own actions and a way back
        assertTrue("$where: an endpoints-page action is on the root page",
            tagged(root, AppsMesh.TAG_TOOL + "endpoints:").isEmpty())
        tagged(root, AppsMesh.TAG_TOOL + "root:endpoints").single().performClick()
        assertControlsGrouped(root, "sub", where, buttonLook)
        tagged(root, AppsMesh.TAG_TOOL + "sub:back").single().performClick()
        assertTrue("$where: back did not return to Apps Mesh", tagged(root, AppsMesh.TAG_TOOL + "sub:").isEmpty())
        tagged(root, AppsMesh.TAG_TOOL + "root:gaps").single().performClick()
        tagged(root, AppsMesh.TAG_TOOL + "sub:back").single().performClick()
        // #809 a member's Details is a sub-page like App API Endpoints, not a dialog:
        // the page swaps, carries its own declared Copy and a way back, and no dialog opens
        val member = fleet.first().id
        tagged(root, "${AppsMesh.TAG_ACTION}details:$member").single().performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("$where: Details did not open as the details sub-page", "${AppsMesh.TAG_SUB}details",
            tagged(root, AppsMesh.TAG_SUB).single().tag)
        assertNull("$where: Details opened a dialog", org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertControlsGrouped(root, "details", where, buttonLook)
        assertControlsGrouped(root, "sub", where, buttonLook)
        assertEquals("$where: Details body is not this member's", 1, tagged(root, AppsMesh.TAG_DETAILS + member).size)
        tagged(root, AppsMesh.TAG_TOOL + "sub:back").single().performClick()
        assertTrue("$where: back did not leave Details", tagged(root, AppsMesh.TAG_TOOL + "details:").isEmpty())
        // static first: every member's card is on the page before any probe answered
        val cards = tagged(root, StoreMesh.TAG_NODE).map { (it.tag as String).removePrefix(StoreMesh.TAG_NODE) }
        assertEquals("$where: not every member is drawn up front", fleet.map { it.id }.sorted(), cards.sorted())
        assertFalse("$where: no probe-age line", tagged(root, AppsMesh.TAG_AGE).isEmpty())
    }

    /** Every declared page / action of [scope] is drawn once, the shared control of its
     *  type, inside the group of its type — and every group holds only its own type. */
    private fun assertControlsGrouped(root: View, scope: String, where: String, buttonLook: String) {
        val drawn = tagged(root, "${AppsMesh.TAG_TOOL}$scope:")
        val want = decl.of(scope).filter { it.type != "filter" }
        assertEquals("$where $scope controls", want.map { it.id }.sorted(),
            drawn.map { (it.tag as String).substringAfterLast(':') }.sorted())
        for (c in want) {
            val v = drawn.single { (it.tag as String).endsWith(":${c.id}") }
            assertEquals("$where: ${c.id} (${c.type}) is drawn in another group",
                "${AppsMesh.TAG_GROUP}$scope:${c.type}", ((v.parent as View).parent as View).tag)
            if (c.type == "page") assertTrue("$where: page ${c.id} is not the shared page button", StoreBar.isPage(v))
            else {
                assertTrue("$where: action ${c.id} is not the shared button", StoreBar.isButton(v))
                assertEquals(buttonLook.substringAfter(" r"), look(v).substringAfter(" r"))
            }
        }
        for (g in tagged(root, "${AppsMesh.TAG_GROUP}$scope:")) {
            val type = (g.tag as String).substringAfterLast(':')
            for (v in views(g).filter { StoreBar.isPage(it) || StoreBar.isButton(it) || StoreBar.isChip(it) })
                assertEquals("$where: group $type holds a control of another kind: ${v.tag}", type,
                    when { StoreBar.isPage(v) -> "page"; StoreBar.isButton(v) -> "action"; else -> "filter" })
        }
    }

    @Test
    fun `control - a TextView painted like the button is not the shared component`() {
        // the class check above discriminates: a look-alike fails it
        val style = StoreControls.load(ctx).action
        val real = StoreBar.button(ctx, style, "x", 0, {})
        val fake = TextView(ctx).apply { background = real.background; textSize = 12f; tag = real.tag }
        assertTrue(StoreBar.isButton(real))
        assertFalse(StoreBar.isButton(fake))
        assertFalse("a button reads as a chip", StoreBar.isChip(real))
        val page = StoreBar.page(ctx, StoreControls.load(ctx).page, "i", "x") {}
        assertTrue(StoreBar.isPage(page)); assertFalse(StoreBar.isButton(page)); assertFalse(StoreBar.isPage(real))
        // and a disabled verb is still the shared button, drawn dimmed
        val off = StoreBar.button(ctx, style, "x", 0, null)
        assertTrue(StoreBar.isButton(off)); assertFalse(off.isEnabled); assertTrue(off.alpha < 1f)
        // Base64 of the baked fleet is what both pages draw from
        assertTrue(String(Base64.decode(b64, Base64.DEFAULT)).contains("\"apps\""))
    }
}
