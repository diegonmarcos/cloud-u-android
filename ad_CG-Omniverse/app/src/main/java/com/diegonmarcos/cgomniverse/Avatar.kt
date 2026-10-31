package com.diegonmarcos.cgomniverse

import com.diegonmarcos.superapp.cgc.AvatarEngine
import com.diegonmarcos.superapp.cgc.AvatarEngines
import com.diegonmarcos.superapp.cgc.CgcConfig
import com.diegonmarcos.superapp.cgc.CgcSpec
import org.json.JSONObject

/** The Avatar editor's declaration (data/avatar.json), parsed by libs:cgc-3d. */
typealias AvatarSpec = CgcSpec

/**
 * The Avatar tab's configuration: one option id per declared category (data/avatar.json), saved as that map; the scene receives
 * libs:cgc-3d's scene ops for it. A saved id the declaration no longer has falls back to the default, so an old save never draws
 * a broken avatar. The rules live in libs:cgc-3d (CgcConfig, mutation-proven); this is the app's thin facade over them.
 */
object Avatar {
    fun spec(json: String): AvatarSpec = CgcSpec.parse(json)

    fun engine(spec: AvatarSpec): AvatarEngine = AvatarEngines.forId(spec.engine)

    fun load(spec: AvatarSpec, saved: String?): Map<String, String> = CgcConfig.load(spec, saved)

    fun save(cfg: Map<String, String>): String = CgcConfig.save(cfg)

    fun set(spec: AvatarSpec, cfg: Map<String, String>, category: String, id: String): Map<String, String> =
        CgcConfig.set(spec, cfg, category, id, engine(spec))

    fun soon(spec: AvatarSpec, category: String): Boolean = spec.category(category)?.let { CgcConfig.soon(it, engine(spec)) } ?: true

    /** What cgc.js apply() receives. */
    fun sceneOps(spec: AvatarSpec, cfg: Map<String, String>): String = CgcConfig.ops(spec, cfg, engine(spec)).toString()

    /** What cgc.js boot() receives: the declared scene with the chrome's insets (CSS px) the model must stay clear of. */
    fun sceneConfig(spec: AvatarSpec, topCss: Int, bottomCss: Int): String =
        JSONObject(spec.scene).put("insets", JSONObject().put("top", topCss).put("bottom", bottomCss)).toString()
}
