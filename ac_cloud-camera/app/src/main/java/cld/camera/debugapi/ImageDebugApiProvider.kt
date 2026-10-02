package cld.camera.debugapi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import cld.camera.BuildConfig
import cld.camera.analyzer.ImageContentScanner
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #772 /api/image/recognize?route=ml|openrouter&path=<file> on the fleet debug API, for checks
 * with the screen locked: the same engine call the gallery's Scan contents makes, on the given
 * route or the user's. A relative path is under this app's files. Registered from a provider's
 * onCreate (the DriveDebugApiProvider trick) so the route exists even if startup later throws.
 * Not a real provider and not exported.
 */
class ImageDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        // A debug facility must never take the host app down.
        runCatching {
            AppDebugServer.route(
                BuildConfig.DEBUG_API_IMAGE_GROUP,
                listOf(AppDebugServer.Op("recognize", "route=<ml|openrouter>&path=<file>", "recognise an image through the shared engine, on a route")),
            ) { op, q -> if (op == "recognize") recognize(app, q).toString() else null }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun recognize(ctx: Context, q: Map<String, String>): JSONObject {
            val route = q["route"]?.takeIf { it.isNotBlank() }
            if (route != null && route !in RecognitionConfig.routes()) return JSONObject().put("ok", false).put("error", "route must be one of ${RecognitionConfig.routes().keys}")
            val path = q["path"].orEmpty()
            if (path.isBlank()) return JSONObject().put("ok", false).put("error", "path is required: a file this app can read (relative = under its files)")
            val file = if (path.startsWith("/")) File(path) else File(ctx.filesDir, path)
            if (!file.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${file.path}")
            val scanner = ImageContentScanner(ctx)
            return json(scanner.recognize(file, route))
                .put("path", file.path)
                .put("chosen_route", RecognitionPrefs.route(ctx)).put("chosen_model", RecognitionPrefs.model(ctx))
                .put("engine", scanner.status() ?: "ready")
        }

        /** The uniform result, field by field (the same shape Cloud Calc's route reports). */
        fun json(r: Recognition): JSONObject = JSONObject()
            .put("ok", r.ok).put("route", r.route).put("requested", r.requested).put("fell_back", r.fellBack).put("reason", r.reason)
            .put("labels", JSONArray().apply { r.labels.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } })
            .put("boxes", JSONArray().apply { r.boxes.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) } })
            .put("text", r.text)
            .put("colours", JSONArray().apply { r.colours.forEach { put(JSONObject().put("hex", it.hex).put("name", it.name).put("share", it.share)) } })
            .put("barcode", r.barcode?.let { JSONObject().put("format", it.format).put("raw", it.rawValue) } ?: JSONObject.NULL)
            .put("answers", JSONObject().apply { r.answers.forEach { (k, opts) -> put(k, JSONArray().apply { opts.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } }) } })
            .put("model", r.model).put("latency_ms", r.latencyMs).put("error", r.error ?: JSONObject.NULL)
    }
}
