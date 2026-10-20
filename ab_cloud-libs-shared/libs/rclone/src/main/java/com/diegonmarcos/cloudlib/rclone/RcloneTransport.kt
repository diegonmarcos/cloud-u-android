package com.diegonmarcos.cloudlib.rclone

import org.json.JSONObject

/**
 * How [RcloneRunner] reaches the engine: JSON in, JSON out. The Android implementation binds
 * Cloud-Lib-Rclone-Engine.apk ([RcloneLink]); the JVM suite hands in a fake, so everything the
 * runner does with an answer (chunks, the rewritten rclone.conf, a job's polling) is tested.
 */
interface RcloneTransport {
    /** Null when the engine can be called, else the sentence that says what to install or update. Costs no bind. */
    fun unavailable(): String?

    /** One bind held for several calls (a long listing's chunks, a job). Throws [java.io.IOException] with the same sentence. */
    fun session(): Session

    /** The engine's methods (IRcloneEngine.call by name). The answer's "ok" is NOT checked here. */
    interface Session : AutoCloseable {
        /** Throws [java.io.IOException] when the engine cannot be reached. */
        fun run(request: JSONObject): JSONObject
        fun outChunk(request: JSONObject): JSONObject
        fun jobStart(request: JSONObject): JSONObject
        fun jobPoll(request: JSONObject): JSONObject
        fun jobCancel(request: JSONObject): JSONObject
        override fun close()
    }
}
