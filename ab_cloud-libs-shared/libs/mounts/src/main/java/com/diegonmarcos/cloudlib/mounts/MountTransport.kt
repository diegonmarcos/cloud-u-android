package com.diegonmarcos.cloudlib.mounts

import org.json.JSONObject

/**
 * How [EngineFs] reaches the mounts engine: JSON in, JSON out. The Android implementation binds
 * Cloud-Lib-Mounts-Engine.apk ([MountLink]); the JVM suite hands in a fake, so everything the client does
 * with an answer (the entries decoded, a long listing in chunks, a known_hosts the engine extended, a
 * connection the engine lost) is tested.
 */
interface MountTransport {
    /** Null when the engine can be called, else the sentence that says what to install or update. Costs no bind. */
    fun unavailable(): String?

    /** One bind held for one step and its chunks. Throws [java.io.IOException] with the same sentence. */
    fun session(): Session

    /** The engine's methods. The answer's "ok" is NOT checked here. */
    interface Session : AutoCloseable {
        /** Throws [java.io.IOException] when the engine cannot be reached. */
        fun open(request: JSONObject): JSONObject
        fun fs(request: JSONObject): JSONObject
        fun chunk(request: JSONObject): JSONObject
        fun closeHandle(request: JSONObject): JSONObject
        override fun close()
    }
}
