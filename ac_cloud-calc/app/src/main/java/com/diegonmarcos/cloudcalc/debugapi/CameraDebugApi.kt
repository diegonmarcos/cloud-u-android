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
import com.google.ar.core.ArCoreApk
import org.json.JSONObject
import java.io.File

/**
 * The Camera tools on the fleet debug API (#772), for checks with the screen locked:
 *
 *   /api/camera/status                 camera permission, ARCore availability, the image engine's
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
            listOf(AppDebugServer.Op("recognize", "route=<ml|openrouter>&path=<file, or last>&context=<text>", "recognise an image through the shared engine, on a route")),
        ) { op, q ->
            when (op) {
                "recognize" -> recognize(app, q).toString()
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
            .put("arcore", runCatching { ArCoreApk.getInstance().checkAvailability(ctx).name }.getOrElse { "error: ${it.message}" })
            .put("ar_offered", CameraDecl.config.arEnabled)
            .put("image_engine", Vision.status(ctx) ?: "ready")
            .put("route", RecognitionPrefs.route(ctx))
            .put("model", RecognitionPrefs.model(ctx))
            .put("routes", JSONObject(RecognitionConfig.routes()))
            .put("last_photo", if (last.isFile) JSONObject().put("path", last.path).put("bytes", last.length()).put("modified", last.lastModified()) else JSONObject.NULL)
            .put("level", tilt?.let { JSONObject().put("tilt_deg", it.tiltDeg).put("pitch_deg", it.pitchDeg).put("roll_deg", it.rollDeg).put("edge_deg", it.edgeDeg) } ?: JSONObject.NULL)
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
    }
}
