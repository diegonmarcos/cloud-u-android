package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The avatar configuration: the declared default, a saved configuration made whole, colours resolved for the scene. */
class AvatarTest {
    private val spec = Avatar.spec(listOf(File("../data/avatar.json"), File("data/avatar.json")).first { it.exists() }.readText())

    @Test fun theDeclarationIsComplete() {
        val ids = spec.groups.map { it.id }
        assertEquals(listOf("body", "height", "skin", "hair", "hair_color", "outfit", "top_color", "bottom_color", "accessory", "pose"), ids)
        spec.groups.forEach { g ->
            assertTrue(g.id, g.options.size >= 3 || g.id == "height")
            if (g.kind == Avatar.KIND_COLOR) assertTrue(g.id, g.options.all { it.color!!.matches(Regex("#[0-9a-f]{6}")) })
            assertTrue(g.id, g.options.any { it.id == spec.defaults[g.id] })
        }
    }

    @Test fun nothingSavedIsTheDefault() {
        assertEquals(spec.defaults, Avatar.load(spec, null))
        assertEquals(spec.defaults, Avatar.load(spec, "not json"))
    }

    @Test fun aSaveRoundTripsAndUnknownIdsFallBack() {
        val cfg = Avatar.set(spec, Avatar.set(spec, spec.defaults, "hair", "bun"), "skin", "deep")
        assertEquals(cfg, Avatar.load(spec, Avatar.save(cfg)))
        val stale = JSONObject(Avatar.save(cfg)).put("hair", "gone")
        stale.remove("pose")
        val loaded = Avatar.load(spec, stale.toString())
        assertEquals(spec.defaults["hair"], loaded["hair"])
        assertEquals(spec.defaults["pose"], loaded["pose"])
        assertEquals("deep", loaded["skin"])
    }

    @Test fun setRefusesWhatIsNotDeclared() {
        assertSame(spec.defaults, Avatar.set(spec, spec.defaults, "hair", "nope"))
        assertSame(spec.defaults, Avatar.set(spec, spec.defaults, "nope", "bun"))
    }

    @Test fun theSceneGetsHexColoursAndShapeIds() {
        val scene = JSONObject(Avatar.sceneJson(spec, Avatar.set(spec, spec.defaults, "skin", "deep")))
        assertEquals("#5a3521", scene.getString("skin"))
        assertEquals(spec.defaults["hair"], scene.getString("hair"))
        assertTrue(scene.getString("top_color").startsWith("#"))
        assertEquals(spec.groups.size, scene.length())
    }
}
