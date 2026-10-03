package com.diegonmarcos.superapp.image.mlkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * #799 THE ONE route switch of recognition, for image AND sound, in every app that compiles this
 * contract (Cloud Camera, Cloud Calc): "Model (Jev)" (route `openrouter`, the declared default) or
 * "On-device ML (offline)" (route `ml`), chosen per type and applied on the next call.
 *
 * The rule, in one place: the user's route is tried; a Model route that cannot run (the phone is
 * offline, no OpenRouter token) is not even attempted, and one that fails (timeout, HTTP error, an
 * engine that cannot answer) is replaced, when the declaration allows it, by the on-device answer —
 * which then says `fell_back` and why. Every answer is recorded per type, so the apps' debug routes
 * report the ACTIVE route (the user's choice) and the LAST route that actually answered.
 *
 * The image engine already falls back inside its own recognize (no token, error, timeout), so for
 * images [routed]'s model call is the engine's whole answer and only the offline check happens here;
 * the sound engine has no network, so for sound both halves are the app's.
 */
object RecognitionRoutes {
    const val IMAGE = "image"
    const val SOUND = "sound"
    val TYPES = listOf(IMAGE, SOUND)

    const val OFFLINE = "offline: the phone has no validated internet connection"
    const val NO_TOKEN = "no OpenRouter token: add one in the fleet Account (Profile ▸ AI tokens)"

    /** Whether the Model route is attempted, and when it is not, why. */
    data class Plan(val askModel: Boolean, val reason: String)

    /**
     * [online] and [hasToken] are null when this app cannot tell (Cloud Camera holds neither the
     * network-state permission nor the token): then the model is asked and its own failure decides.
     */
    fun plan(chosen: String, online: Boolean?, hasToken: Boolean?): Plan = when {
        chosen != RecognitionConfig.OPENROUTER -> Plan(false, "")
        online == false -> Plan(false, OFFLINE)
        hasToken == false -> Plan(false, NO_TOKEN)
        else -> Plan(true, "")
    }

    /** The on-device answer standing in for the Model route, saying so. */
    fun fellBack(onDevice: Recognition, reason: String): Recognition =
        onDevice.copy(route = RecognitionConfig.ML, requested = RecognitionConfig.OPENROUTER, fellBack = true, reason = reason)

    /**
     * One recognition on the user's [chosen] route, with the fallback rule above, recorded as [type]'s
     * last route. [onDevice] runs only when it answers; [model] never throws past here.
     */
    fun routed(
        type: String,
        chosen: String,
        online: Boolean?,
        hasToken: Boolean?,
        fallback: Boolean,
        onDevice: () -> Recognition,
        model: () -> Recognition,
        clock: () -> Long = System::currentTimeMillis,
    ): Recognition {
        val p = plan(chosen, online, hasToken)
        val r = when {
            chosen != RecognitionConfig.OPENROUTER -> onDevice()
            !p.askModel -> if (fallback) fellBack(onDevice(), p.reason) else Recognition.failed(RecognitionConfig.OPENROUTER, p.reason)
            else -> {
                val m = runCatching { model() }.getOrElse { Recognition.failed(RecognitionConfig.OPENROUTER, it.message ?: it.toString()) }
                if (m.ok || !fallback) m else fellBack(onDevice(), m.error ?: "the model did not answer")
            }
        }
        return record(type, chosen, r, clock())
    }

    /** What answered last, per type. */
    data class Used(val chosen: String, val route: String, val fellBack: Boolean, val reason: String, val model: String, val ok: Boolean, val atMs: Long)

    private val last = ConcurrentHashMap<String, Used>()

    fun record(type: String, chosen: String, r: Recognition, atMs: Long = System.currentTimeMillis()): Recognition {
        require(type in TYPES) { "unknown recognition type $type (one of $TYPES)" }
        last[type] = Used(chosen, r.route, r.fellBack, r.reason, r.model, r.ok, atMs)
        return r
    }

    fun last(type: String): Used? = last[type]

    /** Tests only: forget every recorded answer. */
    fun reset() = last.clear()

    /** The debug routes' report: the active route and its label, the declared default, and the last route used. */
    fun status(type: String, active: String, routes: Map<String, String>, defaultRoute: String): JSONObject {
        val u = last(type)
        return JSONObject()
            .put("type", type)
            .put("active_route", active)
            .put("active_label", routes[active] ?: active)
            .put("default_route", defaultRoute)
            .put("last", u?.let {
                JSONObject().put("chosen", it.chosen).put("route", it.route).put("label", routes[it.route] ?: it.route)
                    .put("fell_back", it.fellBack).put("reason", it.reason).put("model", it.model).put("ok", it.ok).put("at_ms", it.atMs)
            } ?: JSONObject.NULL)
    }

    /** One line for a result screen: which route answered, and why it was not the chosen one. */
    fun answeredBy(r: Recognition, routes: Map<String, String>): String {
        val by = routes[r.route] ?: r.route
        return if (r.fellBack) "$by — fell back: ${r.reason}" else by
    }

    /**
     * Whether the phone is online: null when this app may not ask (no ACCESS_NETWORK_STATE, as in
     * Cloud Camera), so the Model route is tried and its own failure triggers the fallback.
     */
    fun online(ctx: Context): Boolean? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrNull()
    }

    /**
     * #799 the image half for an app: the user's request (RecognitionConfig.request) run through
     * [run] (the engine's recognize). Offline skips the network and asks on device; the engine's own
     * fallback covers no token, timeout and error.
     */
    fun image(ctx: Context, request: JSONObject, run: (JSONObject) -> Recognition): Recognition =
        image(request, online(ctx), run)

    fun image(request: JSONObject, online: Boolean?, run: (JSONObject) -> Recognition): Recognition {
        val chosen = request.optString("route", RecognitionConfig.ML)
        return routed(IMAGE, chosen, online, null, request.optBoolean("fallback", true),
            onDevice = { run(JSONObject(request.toString()).put("route", RecognitionConfig.ML)) },
            model = { run(request) })
    }

    /** [image]'s report for the debug routes. */
    fun imageStatus(active: String): JSONObject =
        status(IMAGE, active, RecognitionConfig.routes(), RecognitionConfig.json.getString("default_route"))
}
