package com.diegonmarcos.superapp.cgc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The declaration's parse and the configuration to scene-ops rules (mutation-proven by cg-omniverse-avatar-proof.yml). */
class CgcConfigTest {
    private val spec = Fixture.spec

    @Test fun parseReadsEveryCategoryAndFoldsUnwiredKindsIntoSoon() {
        assertEquals(listOf("top", "skin", "glasses", "face", "anim", "hair", "later"), spec.categories.map { it.id })
        assertTrue("an unwired kind is coming soon", spec.category("hair")!!.soon)
        assertTrue("declared soon", spec.category("later")!!.soon)
        assertFalse(spec.category("top")!!.soon)
        assertEquals(listOf("outfit_top", "hood"), spec.category("top")!!.targets)
        assertEquals(null, spec.category("top")!!.options.first().color)
        assertEquals("Coming soon", spec.text("soon"))
        assertEquals("fallback", spec.text("absent", "fallback"))
        assertFalse(spec.ui.containsKey("_doc"))
        assertEquals("test/model", spec.ai!!.model)
        assertEquals(JSONObject("{\"model\":{\"url\":\"m.glb\"}}").toString(), JSONObject(spec.scene).toString())
    }

    @Test fun aMalformedDeclarationIsEmpty() {
        val s = CgcSpec.parse("not json")
        assertTrue(s.categories.isEmpty())
        assertEquals(null, s.ai)
    }

    @Test fun defaultsAreTheDeclaredOnesNotTheFirstOption() {
        assertEquals("navy", spec.defaults["top"])
        assertEquals(spec.defaults, CgcConfig.load(spec, null))
        assertEquals(spec.defaults, CgcConfig.load(spec, "[]"))
        assertEquals("first option when the default is not declared", "original",
            CgcSpec.parse(Fixture.JSON.replace("\"top\": \"navy\"", "\"top\": \"gone\"")).defaults["top"])
    }

    @Test fun aSaveRoundTripsAndAnUndeclaredIdFallsBack() {
        val cfg = CgcConfig.set(spec, CgcConfig.set(spec, spec.defaults, "top", "forest"), "glasses", "off")
        assertEquals(cfg, CgcConfig.load(spec, CgcConfig.save(cfg)))
        val stale = JSONObject(CgcConfig.save(cfg)).put("top", "neon").put("glasses", "off")
        stale.remove("anim")
        val loaded = CgcConfig.load(spec, stale.toString())
        assertEquals("navy", loaded["top"])
        assertEquals("off", loaded["glasses"])
        assertEquals("idle-opt", loaded["anim"])
    }

    @Test fun setRefusesWhatIsNotDeclaredOrNotWired() {
        val d = spec.defaults
        assertEquals(d, CgcConfig.set(spec, d, "top", "neon"))
        assertEquals(d, CgcConfig.set(spec, d, "nope", "navy"))
        assertEquals("a soon category keeps its value", d, CgcConfig.set(spec, d, "later", "white"))
        assertEquals("forest", CgcConfig.set(spec, d, "top", "forest")["top"])
    }

    @Test fun anEngineThatCannotDrawAKindMakesItSoon() {
        val limited = object : AvatarEngine {
            override val id = "x"; override val formats = setOf("glb"); override val kinds = setOf(CgcSpec.KIND_CLIP); override val available = true
        }
        assertTrue(CgcConfig.soon(spec.category("top")!!, limited))
        assertFalse(CgcConfig.soon(spec.category("anim")!!, limited))
        assertFalse(CgcConfig.soon(spec.category("top")!!, ModelFileEngine))
        assertFalse(CgcConfig.ops(spec, spec.defaults, limited).has("colors"))
        assertEquals(spec.defaults, CgcConfig.set(spec, spec.defaults, "top", "forest", limited))
    }

    @Test fun opsOfTheDefaults() {
        val o = CgcConfig.ops(spec, spec.defaults)
        assertEquals("idle", o.getString("clip"))
        assertEquals("#22345e", o.getJSONObject("colors").getString("outfit_top"))
        assertEquals("#22345e", o.getJSONObject("colors").getString("hood"))
        assertTrue("the original texture is a null tint", o.getJSONObject("tints").isNull("AvatarHead"))
        assertFalse("a tint is not a colour swap", o.getJSONObject("colors").has("AvatarHead"))
        assertTrue(o.getJSONObject("visible").getBoolean("glasses"))
        assertFalse("a soon category emits nothing", o.getJSONObject("colors").has("shoes"))
    }

    @Test fun opsOfAPersonalisedAvatar() {
        var cfg = spec.defaults
        cfg = CgcConfig.set(spec, cfg, "top", "original")
        cfg = CgcConfig.set(spec, cfg, "skin", "tan")
        cfg = CgcConfig.set(spec, cfg, "glasses", "off")
        cfg = CgcConfig.set(spec, cfg, "anim", "dance-opt")
        cfg = CgcConfig.set(spec, cfg, "face", "smile")
        val o = CgcConfig.ops(spec, cfg)
        assertTrue("original = the model's own colour", o.getJSONObject("colors").isNull("outfit_top"))
        assertEquals("#e3ab86", o.getJSONObject("tints").getString("AvatarHead"))
        assertFalse(o.getJSONObject("visible").getBoolean("glasses"))
        assertEquals("the option's clip, not its id", "dance", o.getString("clip"))
        val m = o.getJSONObject("morphs")
        assertEquals(0.8, m.getDouble("mouthSmileLeft"), 1e-9)
        assertEquals("another option's blendshape is reset", 0.0, m.getDouble("jawOpen"), 1e-9)
    }

    @Test fun morphValuesAreClampedAndNeutralZeroesEverything() {
        val shout = CgcConfig.ops(spec, CgcConfig.set(spec, spec.defaults, "face", "shout")).getJSONObject("morphs")
        assertEquals(1.0, shout.getDouble("jawOpen"), 1e-9)
        val neutral = CgcConfig.ops(spec, spec.defaults).getJSONObject("morphs")
        assertEquals(setOf("mouthSmileLeft", "mouthSmileRight", "jawOpen"), neutral.keySet())
        neutral.keySet().forEach { assertEquals(it, 0.0, neutral.getDouble(it), 1e-9) }
    }

    @Test fun engines() {
        assertEquals(ModelFileEngine, AvatarEngines.forId("model-file"))
        assertEquals("not available yet: the model-file engine draws", ModelFileEngine, AvatarEngines.forId("vrm-modular"))
        assertEquals(ModelFileEngine, AvatarEngines.forId("unknown"))
        assertTrue(ModelFileEngine.loads("models/owner.glb"))
        assertTrue(ModelFileEngine.loads("a.FBX?x=1"))
        assertFalse(ModelFileEngine.loads("a.obj"))
        assertTrue(VrmModularEngine.kinds.containsAll(ModelFileEngine.kinds))
    }
}
