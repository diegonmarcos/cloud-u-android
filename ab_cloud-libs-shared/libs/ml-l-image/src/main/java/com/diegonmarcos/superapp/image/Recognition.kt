package com.diegonmarcos.superapp.image.mlkit

import android.content.Context
import com.diegonmarcos.superapp.image.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * #772 THE uniform result of image recognition, whichever route answered: on-device ML (ML Kit
 * labels and boxes, OCR text, a barcode, dominant colours) or an OpenRouter decision model (its
 * category probabilities as [labels], its extra questions as [answers]). Cloud Camera and Cloud
 * Calc render this one shape. [fellBack] says the OpenRouter route failed and on-device answered;
 * [reason] says why.
 *
 * #798 the SAME type answers live detection (each box's tracking [Box.id] and its next-best
 * [Box.alts]) and sound identification (libs:ml-l-sound: labels over a clip, [segments] its timeline).
 */
data class Recognition(
    val ok: Boolean,
    val route: String,
    val requested: String,
    val fellBack: Boolean,
    val reason: String,
    val labels: List<Label>,
    val boxes: List<Box>,
    val text: String,
    val colours: List<Colour>,
    val barcode: BarcodeScan?,
    val answers: Map<String, List<Label>>,
    val model: String,
    val latencyMs: Long,
    val cost: Double?,
    val width: Int,
    val height: Int,
    val error: String?,
    val mode: String = "",
    val segments: List<Segment> = emptyList(),
) {
    data class Label(val label: String, val p: Double)
    data class Box(val label: String, val p: Double, val x: Int, val y: Int, val w: Int, val h: Int, val id: Int? = null, val alts: List<Label> = emptyList())
    data class Segment(val label: String, val p: Double, val startMs: Long, val endMs: Long)
    data class Colour(val hex: String, val name: String, val share: Double)

    companion object {
        /** The engine's answer; anything that is not one is a failed Recognition saying so. */
        fun parse(json: String, requested: String): Recognition {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return failed(requested, "the image engine answered with something that is not JSON")
            fun labels(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.map { Label(it.optString("label"), it.optDouble("p", 0.0)) }
            val err = o.optString("error").takeIf { !o.isNull("error") && it.isNotBlank() }
            val b = o.optJSONObject("barcode")
            return Recognition(
                ok = err == null && o.optBoolean("ok", true),
                route = o.optString("route", requested),
                requested = o.optString("requested", requested),
                fellBack = o.optBoolean("fell_back"),
                reason = o.optString("reason"),
                labels = labels(o.optJSONArray("labels")),
                boxes = o.optJSONArray("boxes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                    .map { Box(it.optString("label"), it.optDouble("p", 0.0), it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h"),
                        if (it.isNull("id")) null else it.optInt("id"), labels(it.optJSONArray("alts"))) },
                text = o.optString("text"),
                colours = o.optJSONArray("colours")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                    .map { Colour(it.optString("hex"), it.optString("name"), it.optDouble("share", 0.0)) },
                barcode = b?.optString("raw")?.let { raw -> BarcodeScan(b.optString("format", "UNKNOWN"), raw, BarcodePayloadParser.parse(raw)) },
                answers = o.optJSONObject("answers")?.let { a -> a.keys().asSequence().associateWith { labels(a.optJSONArray(it)) } }.orEmpty(),
                model = o.optString("model"),
                latencyMs = o.optLong("latency_ms"),
                cost = if (o.has("cost") && !o.isNull("cost")) o.optDouble("cost") else null,
                width = o.optInt("width"),
                height = o.optInt("height"),
                error = err,
                mode = o.optString("mode"),
                segments = o.optJSONArray("segments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                    .map { Segment(it.optString("label"), it.optDouble("p", 0.0), it.optLong("start_ms"), it.optLong("end_ms")) },
            )
        }

        fun failed(requested: String, why: String) = Recognition(
            false, requested, requested, false, "", emptyList(), emptyList(), "", emptyList(), null, emptyMap(), "", 0, null, 0, 0, why,
        )
    }
}

/**
 * libs:ml-l-image/recognition.json, baked: the routes, the default, and every part of a request
 * the engine needs. [request] builds one from the user's choice; the engine keeps no copy.
 */
object RecognitionConfig {
    const val ML = "ml"
    const val OPENROUTER = "openrouter"

    val json: JSONObject by lazy { JSONObject(String(java.util.Base64.getDecoder().decode(BuildConfig.IMAGE_RECOGNITION_B64), Charsets.UTF_8)) }

    /** Route id → its label, in declared order. */
    fun routes(decl: JSONObject = json): Map<String, String> =
        decl.getJSONObject("routes").let { r -> r.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { r.getString(it) } }

    fun defaultModel(decl: JSONObject = json): String = decl.getJSONObject("openrouter").getString("default_model")

    /** #798 the live-identification modes, in declared order, and the one a viewfinder opens in. */
    fun detectModes(decl: JSONObject = json): List<String> = decl.getJSONObject("detect").getJSONArray("modes").let { a -> (0 until a.length()).map { a.getString(it) } }
    fun defaultDetectMode(decl: JSONObject = json): String = decl.getJSONObject("detect").getString("default_mode")

    /**
     * #798 a detect request: the [mode], whether it is one of a [live] viewfinder's successive frames
     * (stream mode, tracking ids, the smaller live_side) and the declaration's detect block. Always on
     * device; the user's route applies to a saved photo through [request].
     */
    fun detectRequest(mode: String, live: Boolean, decl: JSONObject = json): JSONObject {
        require(mode in detectModes(decl)) { "unknown detect mode $mode (one of ${detectModes(decl)})" }
        val d = JSONObject(decl.getJSONObject("detect").toString())
        if (live) d.put("max_side", d.getInt("live_side"))
        return JSONObject().put("mode", mode).put("stream", live).put("detect", d)
    }

    /**
     * What the engine is sent: the route, the model (a blank one means the declared default),
     * the optional caller-held token (else the engine reads the fleet Account's), free context,
     * and the declaration's ml / colours / openrouter blocks.
     */
    fun request(route: String, model: String, token: String? = null, context: String = "", decl: JSONObject = json): JSONObject {
        require(route in routes(decl)) { "unknown recognition route $route (one of ${routes(decl).keys})" }
        return JSONObject()
            .put("route", route)
            .put("model", model.ifBlank { defaultModel(decl) })
            .put("fallback", decl.optBoolean("fallback_to_ml", true))
            .put("context", context)
            .put("ml", decl.getJSONObject("ml"))
            .put("colours", decl.getJSONObject("colours"))
            .put("openrouter", decl.getJSONObject("openrouter"))
            .apply { if (!token.isNullOrBlank()) put("token", token) }
    }
}

/** The user's route and model, per app (Configs ▸ Image recognition route); the declaration's default until chosen. */
object RecognitionPrefs {
    private const val PREFS = "cloud_image_recognition"

    fun route(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("route", null)?.takeIf { it in RecognitionConfig.routes() }
            ?: RecognitionConfig.json.getString("default_route")

    fun model(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", null).orEmpty().ifBlank { RecognitionConfig.defaultModel() }

    fun set(ctx: Context, route: String, model: String) {
        require(route in RecognitionConfig.routes()) { "unknown recognition route $route" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("route", route).putString("model", model.trim()).commit()
    }
}
