package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Boards > My-PM card ids: the declarations carry the new names and the retired ids still resolve. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardsRenameTest {
    private fun myPm(): JSONArray {
        val root = JSONObject(File("../build.json").readText())
        val secs = root.getJSONObject("ui").getJSONArray("sections")
        for (i in 0 until secs.length()) secs.getJSONObject(i).let { if (it.optString("id") == "boards") return it.getJSONArray("stack_my-pm") }
        throw AssertionError("no boards section")
    }

    private fun card(title: String): JSONObject {
        val a = myPm()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("title") == title) return a.getJSONObject(i)
        throw AssertionError("no My-PM card titled $title")
    }

    private fun aliases(c: JSONObject) = c.getJSONArray("aliases").let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test fun `the three cards carry the new names and ids`() {
        assertEquals("myburo/myfin", card("MyBuro/MyFin").getString("anchor"))
        assertEquals("myhealth", card("MyHealth").getString("anchor"))
        assertEquals("navigation/tracker", card("Navigation/Tracker").getString("anchor"))
        val titles = (0 until myPm().length()).map { myPm().getJSONObject(it).optString("title") }
        for (old in listOf("Myfin · Cloud-Me Buro ▸ Fin", "My-health", "Nav tracker")) assertFalse("$old still declared", old in titles)
        assertEquals("board_steps", card("MyHealth").getString("kind"))
        assertEquals("board_places", card("Navigation/Tracker").getString("kind"))
    }

    @Test fun `retired ids resolve to the new anchors`() {
        val panels = (0 until myPm().length()).map { i ->
            val c = myPm().getJSONObject(i)
            Sections.StackPanel(kind = c.getString("kind"), title = c.getString("title"), anchor = c.optString("anchor"),
                aliases = if (c.has("aliases")) aliases(c) else emptyList())
        }
        assertEquals("myburo/myfin", PageAnchors.resolve(panels, "my-fin"))
        assertEquals("myburo/myfin", PageAnchors.resolve(panels, "myfin"))
        assertEquals("myhealth", PageAnchors.resolve(panels, "my-health"))
        assertEquals("navigation/tracker", PageAnchors.resolve(panels, "nav-tracker"))
        assertEquals("navigation/tracker", PageAnchors.resolve(panels, "navigation/tracker"))
        assertNotNull(card("MyHealth"))
    }
}
