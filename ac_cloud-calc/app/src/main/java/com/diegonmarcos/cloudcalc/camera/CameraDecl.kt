package com.diegonmarcos.cloudcalc.camera

import com.diegonmarcos.cloudcalc.BuildConfig
import org.json.JSONObject

/**
 * build.json::camera (#772), decoded once: the reference objects, the capture size, the level's
 * tolerance and sampling, the colour sample, where OCR numbers go, and whether AR is offered.
 * [parse] is pure so DeclarationsTest runs it against this repository's own build.json.
 */
object CameraDecl {
    data class Ref(val id: String, val label: String, val mm: Double)

    data class Config(
        val references: List<Ref>,
        val maxSide: Int,
        val jpegQuality: Int,
        val levelToleranceDeg: Double,
        val levelSampleMs: Int,
        val colourSamplePx: Int,
        val ocrSendTo: String,
        val arEnabled: Boolean,
    )

    val config: Config by lazy { parse(String(java.util.Base64.getDecoder().decode(BuildConfig.CAMERA_CONFIG_B64), Charsets.UTF_8)) }

    fun parse(json: String): Config {
        val o = JSONObject(json)
        val refs = o.getJSONArray("references")
        return Config(
            references = (0 until refs.length()).map { refs.getJSONObject(it) }.map { Ref(it.getString("id"), it.getString("label"), it.getDouble("mm")) },
            maxSide = o.getJSONObject("capture").getInt("max_side"),
            jpegQuality = o.getJSONObject("capture").getInt("jpeg_quality"),
            levelToleranceDeg = o.getJSONObject("level").getDouble("tolerance_deg"),
            levelSampleMs = o.getJSONObject("level").getInt("sample_ms"),
            colourSamplePx = o.getJSONObject("colour").getInt("sample_px"),
            ocrSendTo = o.getJSONObject("ocr").getString("send_to_mode"),
            arEnabled = o.getJSONObject("ar").getBoolean("enabled"),
        )
    }
}
