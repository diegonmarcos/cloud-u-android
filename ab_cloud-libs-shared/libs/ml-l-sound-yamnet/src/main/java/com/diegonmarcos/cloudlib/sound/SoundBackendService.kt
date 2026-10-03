package com.diegonmarcos.cloudlib.sound

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.sound.ISoundEngine
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * #798 [Classifier] over [Yamnet] behind libs:ml-l-sound's ISoundEngine, shipped in
 * Cloud-Lib-Ml-L-Sound-Yamnet.apk. Cloud Calc and Cloud Camera bind this through the contract
 * module's client and compile nothing of this module, so an edit here ships this APK alone.
 *
 * THE CLIP ARRIVES AS A DESCRIPTOR the caller opened (this process cannot open the caller's
 * files); it is read to the end, capped at [MAX_CLIP_BYTES].
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest. Not a DataBackendService because core's
 * IDataBackend carries strings only, and the clip is a descriptor.
 */
class SoundBackendService : Service() {

    private val yamnet by lazy { Yamnet(applicationContext) }
    private val classifier by lazy {
        Classifier(yamnet, { yamnet.labels }, BuildConfig.YAMNET_NAME, BuildConfig.YAMNET_WINDOW, BuildConfig.YAMNET_RATE, BuildConfig.YAMNET_HOP)
    }

    fun methodNames(): Array<String> = arrayOf(CLASSIFY, INFO)

    fun dispatch(method: String, audio: ByteArray, request: String = "{}"): String = when (method) {
        CLASSIFY -> classifier.json(audio, request)
        INFO -> info()
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    /** What this engine runs, for a caller's status line and the debug routes. */
    private fun info(): String = JSONObject()
        .put("model", BuildConfig.YAMNET_NAME).put("classes", yamnet.labels.size)
        .put("sample_rate", BuildConfig.YAMNET_RATE).put("window", BuildConfig.YAMNET_WINDOW).put("hop", BuildConfig.YAMNET_HOP)
        .put("methods", org.json.JSONArray(methodNames().toList()))
        .toString()

    private val binder = object : ISoundEngine.Stub() {
        override fun call(method: String?, request: String?, audio: ParcelFileDescriptor?): String =
            runCatching { dispatch(method.orEmpty(), audio?.let { readAll(it) } ?: ByteArray(0), request ?: "{}") }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun readAll(audio: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(audio).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_CLIP_BYTES) { "the clip is larger than ${MAX_CLIP_BYTES / (1024 * 1024)} MB" }
            }
            out.toByteArray()
        }

    private companion object {
        const val CLASSIFY = "classify"
        const val INFO = "info"

        /** ponytail: one whole clip in memory; 30 s of 48 kHz stereo 16-bit is ~5.8 MB, 32 MB refuses a runaway stream. */
        const val MAX_CLIP_BYTES = 32 * 1024 * 1024
    }
}
