package com.diegonmarcos.superapp.onehand

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #537 — the left edge's Drive sector, asserted on what the menu RESOLVES,
 * not on what build.json declares.
 *
 * Third report of one defect (#284, #404, #537). Every earlier tester read
 * onehand.handles[] — the DEFAULT — and the default has said
 * app:com.diegonmarcos.clouddrive since #302/#304. But the menu draws
 * [OneHandConfig.effective], where a stored override beats the default for
 * ever, and the picker offered `action:section:drive` from 2026-09-10 to
 * 2026-09-13. A phone holding that override got a dead section, a raw label and
 * no glyph while every tester stayed green, because none of them read the
 * value the finger actually fires.
 *
 * So this seeds the store with that historical value — an INPUT, the thing a
 * real device holds — and reads the resolved sector back. Every EXPECTED value
 * is derived from the shipped `BuildConfig.ONEHAND_CONFIG_B64`, never written
 * here: delete the retired_targets row from build.json, or the lookup in
 * effective(), and the first test goes red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OneHandResolvedTargetTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val shipped = OneHandConfig.decode(BuildConfig.ONEHAND_CONFIG_B64)
    private val left get() = shipped.handles.first { it.edge == OneHandConfig.Edge.LEFT }

    /** What a device picked in the 2026-09-10..13 window still holds. */
    private val stale = "action:section:drive"

    @Before
    fun clean() {
        ctx.getSharedPreferences("onehand_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store(slot: String, value: String) {
        // The prune marker is set so pruneSpuriousNones cannot be what changes
        // the answer — this test is about retired targets, not stored NONEs.
        ctx.getSharedPreferences("onehand_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("spurious_nones_pruned_v1", true)
            .putString("${left.id}.$slot", value).commit()
    }

    private fun resolved(slot: String): GestureAction? =
        OneHandConfig.effective(ctx).handles.first { it.id == left.id }.gestures[slot]

    @Test
    fun `a stale Drive override resolves to the standalone APK with its own glyph`() {
        val successor = shipped.retiredTargets[stale]
        assertNotNull(
            "build.json::onehand.retired_targets does not retire $stale — a phone holding it " +
                "stays on a section that no longer exists. Declared: ${shipped.retiredTargets.keys}",
            successor,
        )
        val slot = left.gestures.entries.firstOrNull { it.value == successor }?.key
        assertNotNull(
            "no left-edge sector DECLARES the successor $successor, so retiring into it moves " +
                "the owner somewhere the menu does not offer. Left defaults: ${left.gestures}",
            slot,
        )

        store(slot!!, stale)
        val got = resolved(slot)

        assertEquals("left.$slot with $stale stored resolved to ${got?.serialize()}", successor, got)
        assertTrue("Drive must LAUNCH the APK, not route into the host: $got", got is GestureAction.OpenApp)

        // The glyph the overlay draws for it when the APK is absent — the
        // catalogue entry on the same normalised key OneHandAccessibilityService
        // uses, resolved to a real drawable in THIS app's resources.
        val key = "action:" + got!!.serialize()
        val icon = shipped.appActions.firstOrNull { it.key == key }?.icon.orEmpty()
        assertTrue("no declared glyph for $key in action_catalogue", icon.isNotBlank())
        assertNotEquals(
            "declared glyph '$icon' is not a drawable in ${ctx.packageName}",
            0, ctx.resources.getIdentifier(icon, "drawable", ctx.packageName),
        )
    }

    /** The other direction: the map must not flatten real choices, or the
     *  first test could be satisfied by effective() ignoring the store. */
    @Test
    fun `a live override is left alone`() {
        val slot = left.gestures.keys.first()
        val choice = shipped.handles.flatMap { it.gestures.values }
            .first { it != left.gestures[slot] && it.serialize() !in shipped.retiredTargets }
        store(slot, choice.serialize())
        assertEquals(choice, resolved(slot))
    }
}
