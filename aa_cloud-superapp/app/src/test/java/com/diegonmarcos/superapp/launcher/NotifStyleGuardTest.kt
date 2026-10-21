package com.diegonmarcos.superapp.launcher

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1068 guard - the card style tokens match the spec. docs/oneui-notification-spec.md carries a `tokens` block; every line
 * must equal the matching NotifCard constant, and every NotifCard token must be in the block, so a restyle that touches
 * one without the other fails.
 */
class NotifStyleGuardTest {

    private fun spec(): Map<String, String> {
        val f = File(System.getProperty("user.dir"), "docs/oneui-notification-spec.md").takeIf { it.isFile }
            ?: File(System.getProperty("user.dir"), "../docs/oneui-notification-spec.md")
        val text = f.readText()
        val block = text.substringAfter("```tokens\n").substringBefore("```")
        return block.lines().filter { "=" in it }.associate { l -> l.substringBefore("=").trim() to l.substringAfter("=").trim() }
    }

    private fun hex(c: androidx.compose.ui.graphics.Color) = "#%08X".format(c.toArgb())

    @Test
    fun `NotifCard tokens equal the spec tokens`() {
        val actual = mapOf(
            "radius_dp" to NotifCard.radius.value, "pad_dp" to NotifCard.pad.value, "gap_dp" to NotifCard.gap.value,
            "header_icon_dp" to NotifCard.headerIcon.value, "name_sp" to NotifCard.nameSp.value,
            "meta_sp" to NotifCard.metaSp.value, "title_sp" to NotifCard.titleSp.value, "body_sp" to NotifCard.bodySp.value,
            "action_height_dp" to NotifCard.actionHeight.value, "action_radius_dp" to NotifCard.actionRadius.value,
            "action_sp" to NotifCard.actionSp.value, "avatar_dp" to NotifCard.avatar.value,
        )
        val colours = mapOf(
            "surface" to hex(NotifCard.surface), "title" to hex(NotifCard.title), "body" to hex(NotifCard.body),
            "meta" to hex(NotifCard.meta), "pill" to hex(NotifCard.pill),
        )
        val s = spec()
        assertTrue("the spec has no tokens block", s.isNotEmpty())
        assertEquals("every NotifCard token is declared in the spec and no spec token is orphaned",
            (actual.keys + colours.keys).toSet(), s.keys)
        for ((k, v) in actual) assertEquals("token $k", s.getValue(k).toFloat(), v, 0.001f)
        for ((k, v) in colours) assertEquals("token $k", s.getValue(k).uppercase(), v)
    }
}
