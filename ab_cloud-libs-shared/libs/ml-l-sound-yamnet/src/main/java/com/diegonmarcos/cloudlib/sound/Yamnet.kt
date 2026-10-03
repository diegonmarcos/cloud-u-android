package com.diegonmarcos.cloudlib.sound

import android.content.Context
import com.diegonmarcos.superapp.soundtags.ModelZip
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #798 YAMNet on the LiteRT interpreter: one window of [BuildConfig.YAMNET_WINDOW] float samples at
 * 16 kHz in [-1, 1], 521 class scores out. The model is the asset fetchModels verified against
 * data/models.json; its label list is read out of the model's own metadata zip, so index -> name
 * comes from the very file that scores. One interpreter, never closed, calls serialised: a TFLite
 * interpreter is not safe for concurrent use.
 */
internal class Yamnet(private val ctx: Context) : Classifier.Scorer {
    private val model: ByteArray by lazy { ctx.assets.open(BuildConfig.YAMNET_ASSET).use { it.readBytes() } }

    private val interpreter: Interpreter by lazy {
        Interpreter(ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model).also { it.rewind() })
    }

    val labels: List<String> by lazy {
        ModelZip.lines(model, BuildConfig.YAMNET_LABELS).also {
            require(it.size == BuildConfig.YAMNET_CLASSES) {
                "the model's ${BuildConfig.YAMNET_LABELS} names ${it.size} classes; data/models.json declares ${BuildConfig.YAMNET_CLASSES}"
            }
        }
    }

    @Synchronized
    override fun score(window: FloatArray): FloatArray {
        val out = Array(1) { FloatArray(BuildConfig.YAMNET_CLASSES) }
        interpreter.run(window, out)
        return out[0]
    }
}
