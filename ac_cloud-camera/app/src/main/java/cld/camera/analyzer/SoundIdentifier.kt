package cld.camera.analyzer

import android.content.Context
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.sound.SoundCapture
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.sound.SoundEngine
import org.json.JSONObject
import java.io.File

/**
 * #798 the camera's one door onto the fleet's sound identification (libs:ml-l-sound: YAMNet in
 * Cloud-Lib-Ml-L-Sound-Yamnet.apk, fully offline), the ImageContentScanner shape: Identify's Sound
 * mode and /api/sound/classify both come through here. The answer is the same Recognition the
 * image engine answers in.
 */
class SoundIdentifier(context: Context) {
    private val engine = SoundEngine(context.applicationContext)

    /** Listen for the declared capture length (sound.json) and identify what was heard. Needs RECORD_AUDIO. */
    fun listen(ms: Long? = null): Pair<Recognition, ShortArray> {
        val pcm = SoundCapture.record(SoundConfig.captureMs(ms))
        return engine.classify(pcm, SoundConfig.sampleRate()) to pcm
    }

    fun classify(pcm: ShortArray, rate: Int = SoundConfig.sampleRate()): Recognition = engine.classify(pcm, rate)
    fun classify(file: File): Recognition = engine.classify(file)

    /** Null when the engine is ready, else what to do. */
    fun status(): String? = engine.check()
    fun info(): JSONObject = engine.info()
}
