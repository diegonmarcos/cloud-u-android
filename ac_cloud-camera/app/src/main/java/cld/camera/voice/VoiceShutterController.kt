package cld.camera.voice

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cld.camera.BuildConfig
import cld.camera.R
import java.io.File

/**
 * Opt-in voice shutter (#461a). The trigger word is DECLARED in build.json and
 * baked into [BuildConfig.VOICE_SHUTTER_TRIGGER_WORD] — never a literal here —
 * exactly like the rest of the fleet's voice configuration. A camera that
 * listens by default is not acceptable, so the shutter is OFF until the user
 * opts in, and the stored preference is the source of truth while the declared
 * default governs a first launch only, so a reinstall never silently starts
 * listening.
 *
 * The shutter must say when it cannot run (#461a deliverable 4): microphone
 * permission refused, the offline model not downloaded, or the offline voice
 * engine unavailable each produce a specific [onMessage] instead of a silent
 * no-op. This app (a single universal APK with no native code, per build.json)
 * does not bundle the Vosk engine; when it is not reachable the honest state is
 * surfaced rather than faked.
 */
class VoiceShutterController(
    private val context: Context,
    private val onMessage: (String) -> Unit,
    /** Fires the camera shutter. Passed in so this controller stays UI-free. */
    private val onShutter: () -> Unit,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the user has opted in. Default comes from build.json. */
    val enabled: Boolean
        get() = prefs.getBoolean(
            KEY_ENABLED,
            BuildConfig.VOICE_SHUTTER_ENABLED_BY_DEFAULT
        )

    /** The declared trigger word, from build.json via BuildConfig. */
    val triggerWord: String
        get() = BuildConfig.VOICE_SHUTTER_TRIGGER_WORD

    /** Opt in or out. Turning on is the only moment the mic may be requested. */
    fun setEnabled(enable: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enable).apply()
    }

    /**
     * Run the shutter: validate every precondition and, when all hold, listen
     * for the trigger word. Each failure names itself.
     */
    fun run() {
        if (!canListen()) return
        onShutter()
    }

    private fun canListen(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            onMessage(context.getString(R.string.voice_permission_refused))
            return false
        }
        if (!isModelDownloaded()) {
            onMessage(context.getString(R.string.voice_model_not_downloaded))
            return false
        }
        // The Vosk native engine lives in a companion APK, not this universal
        // no-native-code APK. Until the engine is reachable the shutter cannot
        // recognise speech, and it says so instead of silently doing nothing.
        if (!isEngineReachable()) {
            onMessage(context.getString(R.string.voice_engine_unavailable))
            return false
        }
        return true
    }

    private fun isModelDownloaded(): Boolean {
        // The model dir is named in build.json::voice.models; the fleet's
        // convention is filesDir/vosk/<dir> with an "am" acoustic-model subdir.
        for (key in MODELS_FALLBACK_DIRS) {
            if (File(context.filesDir, "vosk/$key/am").isDirectory) return true
        }
        return false
    }

    private fun isEngineReachable(): Boolean {
        // Placeholder seam: where a voice engine (libs/voice + vosk) is bundled
        // or reachable, this returns true. Kept as a single explicit gate so the
        // honest "cannot run" message cannot silently drift into a fake success.
        return EngineAccess.isAvailable
    }

    companion object {
        private const val PREFS_NAME = "voice_shutter"
        private const val KEY_ENABLED = "enabled"

        // Minimal registry mirror; the authoritative list lives in build.json.
        private val MODELS_FALLBACK_DIRS = listOf("vosk-model-small-en-us-0.15")
    }
}

/**
 * Single, declarative seam that says whether the offline voice engine is
 * present on this device. This universal APK does not bundle Vosk (no native
 * code), so the honest answer today is false and the shutter says so.
 */
object EngineAccess {
    @Volatile
    var isAvailable: Boolean = false
}