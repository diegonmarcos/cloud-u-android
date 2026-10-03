package com.diegonmarcos.cloudcalc.debugapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.camera.CameraDecl
import com.diegonmarcos.cloudcalc.camera.Gravity
import com.diegonmarcos.cloudcalc.camera.Vision
import com.diegonmarcos.cloudcalc.measure.Level
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import org.json.JSONObject
import java.io.File

/**
 * The Camera tools on the fleet debug API (#772), for checks with the screen locked:
 *
 *   /api/camera/status                 camera permission, whether ARCore is installed, the image engine's
 *                                      handshake at the recognize contract, the route and model in
 *                                      force, the last photo, and one inclinometer reading
 *   /api/image/recognize?route=ml|openrouter&path=<file>
 *                                      the uniform recognition of a file (path=last: the last photo;
 *                                      a relative path is under this app's files), on the given
 *                                      route or the user's — the same call Identify makes
 *
 * The op is `status`, not `state`: update-ack-guard.json owns that key (GET /api/state).
 */
object CameraDebugApi {
    /** Google Play Services for AR: read from the package list, never asked (its availability query is async and needs Play Services). */
    private const val ARCORE_PACKAGE = "com.google.ar.core"

    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_CAMERA_GROUP,
            listOf(AppDebugServer.Op("status", "", "camera permission, ARCore, image engine handshake, route and model, last photo, one level reading")),
        ) { op, _ ->
            when (op) {
                "status" -> status(app).toString()
                else -> null
            }
        }
        AppDebugServer.route(
            BuildConfig.DEBUG_API_IMAGE_GROUP,
            listOf(
                AppDebugServer.Op("recognize", "route=<ml|openrouter>&path=<file, or last>&context=<text>", "recognise an image through the shared engine, on a route"),
                AppDebugServer.Op("detect", "path=<file, or last>&mode=<objects|labels|text>", "#798 live identification's detection of one image: boxes, labels, tracking ids (on device)"),
                AppDebugServer.Op("route", "[set=<openrouter|ml>]", "#799 the image route: active (the user's), declared default, and the last route that answered"),
            ),
        ) { op, q ->
            when (op) {
                "recognize" -> recognize(app, q).toString()
                "route" -> route(app, q).toString()
                "detect" -> detect(app, q).toString()
                else -> null
            }
        }
    }

    fun status(ctx: Context): JSONObject {
        val last = Vision.last(ctx)
        val g = Gravity.once(ctx, CameraDecl.config.levelSampleMs * 5L)
        val tilt = g?.let { Level.tilt(it.first, it.second, it.third) }
        return JSONObject()
            .put("camera_granted", ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            .put("arcore_installed", runCatching { ctx.packageManager.getPackageInfo(ARCORE_PACKAGE, 0); true }.getOrDefault(false))
            .put("ar_offered", CameraDecl.config.arEnabled)
            .put("image_engine", Vision.status(ctx) ?: "ready")
            .put("route", RecognitionPrefs.route(ctx))
            .put("model", RecognitionPrefs.model(ctx))
            .put("routes", JSONObject(RecognitionConfig.routes()))
            .put("route_status", RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx)))
            .put("last_photo", if (last.isFile) JSONObject().put("path", last.path).put("bytes", last.length()).put("modified", last.lastModified()) else JSONObject.NULL)
            .put("level", tilt?.let { JSONObject().put("tilt_deg", it.tiltDeg).put("pitch_deg", it.pitchDeg).put("roll_deg", it.rollDeg).put("edge_deg", it.edgeDeg) } ?: JSONObject.NULL)
    }

    /** #798 /api/image/detect: the shared engine's detection (contract 3) of one photo, always on device. */
    fun detect(ctx: Context, q: Map<String, String>): JSONObject {
        val mode = q["mode"]?.takeIf { it.isNotBlank() } ?: RecognitionConfig.defaultDetectMode()
        if (mode !in RecognitionConfig.detectModes()) return JSONObject().put("ok", false).put("error", "mode must be one of ${RecognitionConfig.detectModes()}")
        val path = q["path"].orEmpty().ifBlank { "last" }
        val file = when {
            path == "last" -> Vision.last(ctx)
            path.startsWith("/") -> File(path)
            else -> File(ctx.filesDir, path)
        }
        if (!file.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${file.path} — take a photo in Measure ▸ Camera first, or give a path this app can read")
        val r = Vision.detect(ctx, file, mode)
        return Vision.json(r).put("path", file.path).put("mode", r.mode)
            .put("tracking", org.json.JSONArray().apply { r.boxes.forEach { b -> put(JSONObject().put("label", b.label).put("id", b.id ?: JSONObject.NULL)
                .put("alts", org.json.JSONArray().apply { b.alts.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } })) } })
            .put("engine", Vision.detectStatus(ctx) ?: "ready")
    }

    fun recognize(ctx: Context, q: Map<String, String>): JSONObject {
        val route = q["route"]?.takeIf { it.isNotBlank() }
        if (route != null && route !in RecognitionConfig.routes()) return JSONObject().put("ok", false).put("error", "route must be one of ${RecognitionConfig.routes().keys}")
        val path = q["path"].orEmpty().ifBlank { "last" }
        val file = when {
            path == "last" -> Vision.last(ctx)
            path.startsWith("/") -> File(path)
            else -> File(ctx.filesDir, path)
        }
        if (!file.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${file.path} — take a photo in Measure ▸ Camera first, or give a path this app can read")
        return Vision.json(Vision.recognize(ctx, file, route, q["context"].orEmpty())).put("path", file.path)
            .put("routes", RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx)))
    }

    /** #799 /api/image/route[?set=]: the active image route, the declared default, the last route used. */
    fun route(ctx: Context, q: Map<String, String>): JSONObject {
        q["set"]?.takeIf { it.isNotBlank() }?.let { r ->
            if (r !in RecognitionConfig.routes()) return JSONObject().put("ok", false).put("error", "set must be one of ${RecognitionConfig.routes().keys}")
            RecognitionPrefs.set(ctx, r, RecognitionPrefs.model(ctx))
        }
        return RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx)).put("ok", true).put("model", RecognitionPrefs.model(ctx))
    }
}
