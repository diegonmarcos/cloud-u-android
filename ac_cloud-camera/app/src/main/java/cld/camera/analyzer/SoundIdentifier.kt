package cld.camera.analyzer

import android.content.Context
import android.graphics.Bitmap
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import com.diegonmarcos.superapp.sound.SoundCapture
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.sound.SoundEngine
import com.diegonmarcos.superapp.sound.SoundPrefs
import com.diegonmarcos.superapp.sound.SoundRouting
import org.json.JSONObject
import java.io.File

/**
 * #798 the camera's one door onto the fleet's sound identification (libs:ml-l-sound: YAMNet in
 * Cloud-Lib-Ml-L-Sound-Yamnet.apk, fully offline), the ImageContentScanner shape: Identify's Sound
 * mode and /api/sound/classify both come through here. The answer is the same Recognition the
 * image engine answers in.
 *
 * #799 on the user's Sound route (More settings ▸ Sound recognition route): Model (Jev), the default,
 * asks the decision model through the image engine (it holds the network and the fleet Account's
 * token; this app holds neither) with YAMNet's classes and a spectrogram; On-device ML is YAMNet
 * alone. A Model route that cannot answer falls back to YAMNet and says so (SoundRouting).
 */
class SoundIdentifier(context: Context) {
    private val ctx = context.applicationContext
    private val engine = SoundEngine(ctx)
    private val image by lazy { ImageContentScanner(ctx) }

    /** Listen for the declared capture length (sound.json) and identify what was heard. Needs RECORD_AUDIO. */
    fun listen(ms: Long? = null, route: String? = null): Pair<Recognition, ShortArray> {
        val pcm = SoundCapture.record(SoundConfig.captureMs(ms))
        return classify(pcm, SoundConfig.sampleRate(), route) to pcm
    }

    /** [pcm] on [route] (default: the user's choice), with the fallback rule. */
    fun classify(pcm: ShortArray, rate: Int = SoundConfig.sampleRate(), route: String? = null): Recognition =
        SoundRouting.identify(route ?: SoundPrefs.route(ctx), RecognitionRoutes.online(ctx), pcm, rate,
            onDevice = { engine.classify(pcm, rate) },
            viaModel = { request, px, side -> image.recognizeAsGiven(Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888), request) },
            model = RecognitionPrefs.model(ctx))

    /** A WAV file is classified on device: the Model route needs the samples, and debug files are for the engine. */
    fun classify(file: File): Recognition = RecognitionRoutes.record(RecognitionRoutes.SOUND, SoundConfig.ML, engine.classify(file))

    /** Null when the engine is ready, else what to do. */
    fun status(): String? = engine.check()
    fun info(): JSONObject = engine.info()
}
