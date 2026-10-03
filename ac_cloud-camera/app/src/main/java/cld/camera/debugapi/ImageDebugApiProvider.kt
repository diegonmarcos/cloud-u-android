package cld.camera.debugapi

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import cld.camera.BuildConfig
import androidx.core.content.ContextCompat
import cld.camera.analyzer.ImageContentScanner
import cld.camera.analyzer.SoundIdentifier
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import com.diegonmarcos.superapp.sound.SoundCapture
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.sound.SoundPrefs
import com.diegonmarcos.superapp.sound.SoundRouting
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
                listOf(
                    AppDebugServer.Op("recognize", "route=<ml|openrouter>&path=<file>", "recognise an image through the shared engine, on a route"),
                    AppDebugServer.Op("detect", "path=<file>&mode=<objects|labels|text>", "#798 live identification's detection of one image: boxes, labels, tracking ids (on device)"),
                    AppDebugServer.Op("route", "[set=<openrouter|ml>]", "#799 the image route: active (the user's), declared default, and the last route that answered"),
                ),
            ) { op, q ->
                when (op) {
                    "recognize" -> recognize(app, q).toString()
                    "route" -> imageRoute(app, q).toString()
                    "detect" -> detect(app, q).toString()
                    else -> null
                }
            }
        }
        runCatching {
            AppDebugServer.route(
                BuildConfig.DEBUG_API_SOUND_GROUP,
                listOf(
                    AppDebugServer.Op("classify", "ms=<n> | path=<wav> | test=<${SoundCapture.TESTS.joinToString("|")}>[&ms=<n>][&route=<openrouter|ml>]",
                        "#798/#799 identify a sound on the user's route (or route=): Model (Jev) through the image engine, else YAMNet on device; from the microphone, a WAV file (on device), or a synthetic test clip"),
                    AppDebugServer.Op("route", "[set=<openrouter|ml>]", "#799 the sound route: active (the user's), declared default, and the last route that answered"),
                ),
            ) { op, q ->
                when (op) {
                    "classify" -> classify(app, q).toString()
                    "route" -> soundRoute(app, q).toString()
                    else -> null
                }
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** A path this app can read: absolute, or relative to its files. */
        private fun file(ctx: Context, path: String) = if (path.startsWith("/")) File(path) else File(ctx.filesDir, path)

        /** #798 /api/image/detect?path=<file>&mode=objects|labels|text — the same call Identify makes, as a single photo. */
        fun detect(ctx: Context, q: Map<String, String>): JSONObject {
            val mode = q["mode"]?.takeIf { it.isNotBlank() } ?: RecognitionConfig.defaultDetectMode()
            if (mode !in RecognitionConfig.detectModes()) return JSONObject().put("ok", false).put("error", "mode must be one of ${RecognitionConfig.detectModes()}")
            val path = q["path"].orEmpty()
            if (path.isBlank()) return JSONObject().put("ok", false).put("error", "path is required: a file this app can read (relative = under its files)")
            val f = file(ctx, path)
            if (!f.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${f.path}")
            val scanner = ImageContentScanner(ctx)
            return json(scanner.detect(f, mode)).put("path", f.path).put("engine", scanner.detectStatus() ?: "ready")
        }

        /**
         * #798 /api/sound/classify — ms=<n> listens on the microphone (a backgrounded app is handed
         * silence by Android: `peak` 0 says so), path=<wav> reads a file, test=<kind> makes a
         * synthetic clip. The same engine call Identify's Sound mode makes.
         */
        fun classify(ctx: Context, q: Map<String, String>): JSONObject {
            val sound = SoundIdentifier(ctx)
            val route = q["route"]?.takeIf { it.isNotBlank() }
            if (route != null && route !in SoundConfig.routes()) return JSONObject().put("ok", false).put("error", "route must be one of ${SoundConfig.routes().keys}")
            val ms = q["ms"]?.toLongOrNull()
            val test = q["test"]?.takeIf { it.isNotBlank() }
            val path = q["path"]?.takeIf { it.isNotBlank() }
            val out = when {
                path != null -> {
                    val f = file(ctx, path)
                    if (!f.canRead()) return JSONObject().put("ok", false).put("error", "cannot read ${f.path}")
                    json(sound.classify(f)).put("source", "file").put("path", f.path)
                }
                test != null -> {
                    if (test !in SoundCapture.TESTS) return JSONObject().put("ok", false).put("error", "test must be one of ${SoundCapture.TESTS}")
                    val pcm = SoundCapture.testClip(test, SoundConfig.captureMs(ms))
                    json(sound.classify(pcm, route = route)).put("source", "test:$test").put("samples", pcm.size).put("peak", SoundCapture.peak(pcm))
                }
                ms != null -> {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        return JSONObject().put("ok", false).put("error", "RECORD_AUDIO is not granted to this app")
                    val (r, pcm) = sound.listen(ms, route)
                    json(r).put("source", "mic").put("samples", pcm.size).put("peak", SoundCapture.peak(pcm))
                }
                else -> return JSONObject().put("ok", false).put("error", "one of ms=<n>, path=<wav> or test=<${SoundCapture.TESTS.joinToString("|")}> is required")
            }
            return out.put("engine", sound.status() ?: "ready").put("routes", SoundRouting.status(ctx))
        }

        /** #799 /api/image/route[?set=]: the active image route, the declared default, the last route used. */
        fun imageRoute(ctx: Context, q: Map<String, String>): JSONObject {
            q["set"]?.takeIf { it.isNotBlank() }?.let { r ->
                if (r !in RecognitionConfig.routes()) return JSONObject().put("ok", false).put("error", "set must be one of ${RecognitionConfig.routes().keys}")
                RecognitionPrefs.set(ctx, r, RecognitionPrefs.model(ctx))
            }
            return RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx)).put("ok", true).put("model", RecognitionPrefs.model(ctx))
        }

        /** #799 /api/sound/route[?set=]: the active sound route, the declared default, the last route used. */
        fun soundRoute(ctx: Context, q: Map<String, String>): JSONObject {
            q["set"]?.takeIf { it.isNotBlank() }?.let { r ->
                if (r !in SoundConfig.routes()) return JSONObject().put("ok", false).put("error", "set must be one of ${SoundConfig.routes().keys}")
                SoundPrefs.set(ctx, r)
            }
            return SoundRouting.status(ctx).put("ok", true).put("model", RecognitionPrefs.model(ctx))
        }

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
                .put("engine", scanner.status() ?: "ready").put("routes", RecognitionRoutes.imageStatus(RecognitionPrefs.route(ctx)))
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
            // #798 a detection's mode and each box's tracking id and alternatives; a sound answer's timeline
            .put("mode", r.mode)
            .put("tracking", JSONArray().apply { r.boxes.forEach { b -> put(JSONObject().put("label", b.label).put("id", b.id ?: JSONObject.NULL)
                .put("alts", JSONArray().apply { b.alts.forEach { put(JSONObject().put("label", it.label).put("p", it.p)) } })) } })
            .put("segments", JSONArray().apply { r.segments.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("start_ms", it.startMs).put("end_ms", it.endMs)) } })
    }
}
