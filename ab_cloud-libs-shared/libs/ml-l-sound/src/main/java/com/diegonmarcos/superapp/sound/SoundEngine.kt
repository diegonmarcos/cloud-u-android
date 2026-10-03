package com.diegonmarcos.superapp.sound

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #798 the fleet's ONE sound identification, REACHED, NOT CARRIED: YAMNet and the TFLite runtime
 * run only in Cloud-Lib-Ml-L-Sound-Yamnet.apk, so Cloud Calc and Cloud Camera compile this thin
 * client and an engine change republishes that APK and no app.
 *
 * THE CLIP CROSSES AS A FILE DESCRIPTOR of a RIFF/WAVE file the caller opened: a recording held in
 * memory is written once into this app's cache, opened, and unlinked (the ImageScanEngine shape).
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] resolves the declared action in the declared
 * package (engine-client.json, resolved from the fleet manifest at build time and queried in this
 * module's manifest) and reads the CONTRACT it declares. Missing and too-old are different sentences
 * naming the Store.
 *
 * THE ANSWER IS A [Recognition], the same type the image engine answers in: labels with
 * probabilities, plus the timeline as [Recognition.segments]. Never throws.
 *
 * BLOCKING: binding waits on the main thread's delivery, so call off the main thread.
 */
class SoundEngine(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var remote: ISoundEngine? = null
    @Volatile private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { ISoundEngine.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }

    /** Null when the engine is ready (and bound), else the sentence that says what to do. */
    fun check(): String? = check(BuildConfig.SOUND_ENGINE_MIN_CONTRACT)

    fun check(needed: Int): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.SOUND_ENGINE_PACKAGE
        val service = pm.resolveService(Intent(BuildConfig.SOUND_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) "$pkg has no sound engine service — update it from Store ▸ Cloud Constellation ▸ Libs"
                   else "the sound engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
        return if (connect(service.packageName, service.name)) null else "$pkg did not accept the bind"
    }

    @Synchronized
    private fun connect(pkg: String, cls: String): Boolean {
        if (remote != null) return true
        val l = CountDownLatch(1)
        latch = l
        val started = runCatching {
            ctx.bindService(Intent().setClassName(pkg, cls), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!started) return false
        runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return remote != null
    }

    /** Identify a mono 16-bit recording at [sampleRate] Hz. */
    fun classify(pcm: ShortArray, sampleRate: Int, request: JSONObject = SoundConfig.request()): Recognition =
        recognition(request) { descriptorOf(wav16(pcm, sampleRate)) }

    /** Identify a RIFF/WAVE file this app can read. */
    fun classify(file: File, request: JSONObject = SoundConfig.request()): Recognition =
        recognition(request) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }

    /** What the engine runs: model, its class count and the rate it reads; {"error"} when it cannot answer. */
    fun info(): JSONObject = runCatching { JSONObject(ask(INFO, { null }, "{}")) }
        .getOrElse { JSONObject().put("error", it.message ?: it.toString()) }

    private fun recognition(request: JSONObject, open: () -> ParcelFileDescriptor?): Recognition =
        runCatching { Recognition.parse(ask(CLASSIFY, open, request.toString()), SoundConfig.ML) }
            .getOrElse { Recognition.failed(SoundConfig.ML, it.message ?: it.toString()) }

    /** Every engine call: the handshake, then the caller-opened descriptor over the binder. */
    private fun ask(method: String, open: () -> ParcelFileDescriptor?, request: String): String {
        val why = check()
        val r = remote
        if (why != null || r == null) throw IllegalStateException(why ?: "the sound engine is not ready")
        val fd = open()
        if (fd == null && method != INFO) throw IllegalStateException("cannot open the clip")
        return try {
            r.call(method, request, fd) ?: throw IllegalStateException("the sound engine did not answer $method")
        } finally {
            fd?.close()
        }
    }

    /** A recording has no descriptor: write it once into this app's cache, open it, unlink it. */
    private fun descriptorOf(bytes: ByteArray): ParcelFileDescriptor {
        val tmp = File.createTempFile("sound", ".wav", ctx.cacheDir)
        try {
            tmp.writeBytes(bytes)
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** The engine CONTRACT meta-data key (libs/ml-l-sound-yamnet's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (SoundBackendService). Strings, not an import: no consumer
        // compiles the engine, and that is the point.
        const val CLASSIFY = "classify"
        const val INFO = "info"

        const val BIND_TIMEOUT_MS = 4000L

        /** A canonical 44-byte-header PCM16 mono WAV of [pcm] at [rate] Hz. */
        fun wav16(pcm: ShortArray, rate: Int): ByteArray {
            val data = pcm.size * 2
            val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            pcm.forEach { b.putShort(it) }
            return b.array()
        }
    }
}

/**
 * libs:ml-l-sound/sound.json, baked: the routes, the default, the on-device request and the
 * capture and tagging limits. [request] is what the engine is sent; it keeps no thresholds of its own.
 */
object SoundConfig {
    const val ML = "ml"
    const val OPENROUTER = "openrouter"

    val json: JSONObject by lazy { JSONObject(String(java.util.Base64.getDecoder().decode(BuildConfig.SOUND_CONFIG_B64), Charsets.UTF_8)) }

    /** Route id → its label, in declared order. */
    fun routes(decl: JSONObject = json): Map<String, String> =
        decl.getJSONObject("routes").let { r -> r.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { r.getString(it) } }

    fun request(decl: JSONObject = json): JSONObject = JSONObject().put("ml", decl.getJSONObject("ml"))

    /** How long one identification listens: [ms] when given, else the declared default, never over the declared cap. */
    fun captureMs(ms: Long? = null, decl: JSONObject = json): Long {
        val c = decl.getJSONObject("capture")
        return (ms ?: c.getLong("default_ms")).coerceIn(1L, c.getLong("max_ms"))
    }

    fun sampleRate(decl: JSONObject = json): Int = decl.getJSONObject("capture").getInt("sample_rate")

    /** #799 whether a failed Model route is answered on device instead (sound.json::fallback_to_ml). */
    fun fallback(decl: JSONObject = json): Boolean = decl.optBoolean("fallback_to_ml", true)

    /**
     * #799 the Model route of an app with no decision client of its own (Cloud Camera): an image-engine
     * recognize request on the openrouter route, recognition.json's OpenRouter (endpoint, account,
     * default model) asked sound.json's question, with the engine's own fallback OFF — the fallback
     * of a sound is YAMNet's answer, not the spectrogram's photo labels — and no OCR or barcode.
     */
    fun modelRequest(model: String, context: String, image: JSONObject = RecognitionConfig.json, decl: JSONObject = json): JSONObject {
        val r = RecognitionConfig.request(RecognitionConfig.OPENROUTER, model, null, context, image)
        val o = JSONObject(r.getJSONObject("openrouter").toString())
        val s = decl.getJSONObject("openrouter")
        s.keys().asSequence().filterNot { it.startsWith("_") }.forEach { o.put(it, s.get(it)) }
        if (!s.has("questions")) o.remove("questions")
        return r.put("openrouter", o).put("fallback", false)
            .put("ml", JSONObject(r.getJSONObject("ml").toString()).put("ocr", false).put("barcode", false))
    }

    /** #799 what the Model route is told it heard: YAMNet's classes (when it answered), the peak, the length. */
    fun modelContext(onDevice: Recognition?, pcm: ShortArray, sampleRate: Int): String {
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val heard = onDevice?.takeIf { it.ok }?.labels?.joinToString(", ") { "${it.label} ${"%.2f".format(java.util.Locale.ROOT, it.p)}" }
        return "heard_on_device: ${heard ?: "unavailable"}; peak: ${"%.3f".format(java.util.Locale.ROOT, peak / 32768.0)}; length_ms: ${if (sampleRate > 0) pcm.size * 1000L / sampleRate else 0}"
    }

    /**
     * #799 a spectrogram of [pcm] as [w]×[h] opaque grey ARGB pixels (time across, log-spaced
     * frequency up, brighter is louder over the clip's own range): the image the Model route sees.
     * Plain arithmetic (Goertzel per band), so it is tested on the JVM.
     */
    fun spectrogram(pcm: ShortArray, sampleRate: Int, w: Int, h: Int, fminHz: Double = 60.0): IntArray {
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        if (pcm.isEmpty() || w <= 0 || h <= 0 || sampleRate <= 0) return px
        val n = minOf(512, pcm.size)
        val fmax = sampleRate / 2.0
        val db = Array(w) { DoubleArray(h) }
        for (x in 0 until w) {
            val start = if (w > 1) ((pcm.size - n).toLong() * x / (w - 1)).toInt() else 0
            for (y in 0 until h) {
                val f = fminHz * Math.pow(fmax / fminHz, (y + 0.5) / h)
                val c = 2 * Math.cos(2 * Math.PI * f / sampleRate)
                var s1 = 0.0; var s2 = 0.0
                for (i in 0 until n) { val s0 = pcm[start + i] / 32768.0 + c * s1 - s2; s2 = s1; s1 = s0 }
                val power = (s1 * s1 + s2 * s2 - c * s1 * s2) / (n * n)
                db[x][y] = 10 * Math.log10(power + 1e-12)
            }
        }
        val hi = db.maxOf { it.max() }
        val lo = maxOf(db.minOf { it.min() }, hi - 80)
        for (x in 0 until w) for (y in 0 until h) {
            val v = if (hi > lo) ((db[x][y] - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.0
            val g = (v * 255).toInt()
            px[(h - 1 - y) * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return px
    }

    /** The ambient classes a photo is tagged with: the declared number, at or above the declared score. */
    fun tags(r: Recognition, decl: JSONObject = json): List<Recognition.Label> {
        val t = decl.getJSONObject("tags")
        return if (!r.ok) emptyList() else r.labels.filter { it.p >= t.getDouble("min_score") }.take(t.getInt("max"))
    }
}

/** The user's sound route, per app; the declaration's default until chosen. */
object SoundPrefs {
    private const val PREFS = "cloud_sound_identification"

    fun route(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("route", null)?.takeIf { it in SoundConfig.routes() }
            ?: SoundConfig.json.getString("default_route")

    fun set(ctx: Context, route: String) {
        require(route in SoundConfig.routes()) { "unknown sound route $route" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("route", route).commit()
    }
}

/**
 * #799 "What is this sound?" on the user's route for an app with no decision client of its own
 * (Cloud Camera): Model (Jev) through the image engine (it holds the network and the fleet Account's
 * token) with YAMNet's classes as the measurement and the spectrogram as the image; YAMNet answers
 * when the model cannot. Recorded as the sound type's last route (RecognitionRoutes).
 */
object SoundRouting {
    fun identify(
        chosen: String,
        online: Boolean?,
        pcm: ShortArray,
        sampleRate: Int,
        onDevice: () -> Recognition,
        viaModel: (request: JSONObject, spectrogram: IntArray, side: Int) -> Recognition,
        model: String = RecognitionConfig.defaultModel(),
    ): Recognition {
        var heard: Recognition? = null
        val ml = { heard ?: onDevice().also { heard = it } }
        return RecognitionRoutes.routed(RecognitionRoutes.SOUND, chosen, online, null, SoundConfig.fallback(),
            onDevice = ml,
            model = {
                val side = SoundConfig.json.getJSONObject("openrouter").optInt("image_side", 256)
                viaModel(SoundConfig.modelRequest(model, SoundConfig.modelContext(ml(), pcm, sampleRate)), SoundConfig.spectrogram(pcm, sampleRate, side, side), side)
                    .let { r -> if (r.ok) r.copy(route = RecognitionConfig.OPENROUTER, segments = heard?.segments.orEmpty()) else r }
            })
    }

    fun status(ctx: Context): JSONObject =
        RecognitionRoutes.status(RecognitionRoutes.SOUND, SoundPrefs.route(ctx), SoundConfig.routes(), SoundConfig.json.getString("default_route"))
}
