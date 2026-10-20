package com.diegonmarcos.cloudlib.mounts

import java.io.File
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * The names both halves of the mounts engine speak in (#871 move 12), in ONE place: the client
 * ([MountFsFactory], [EngineFs]) builds requests with them, the engine (libs:mounts-engine) answers in them.
 *
 *   open    {"spec": "<MountSpec as JSON text>", "password"?: .., "passphrase"?: .., "key": "<private key text>",
 *            "known_hosts": "<text>"}                       -> {"handle": .., "known_hosts"?: "<text, if extended>"}
 *   fs      {"handle": .., "verb": "<one of VERBS>", "args": {...}} -> {"result"?: "<text>", "more"?: true, "id"?: ..}
 *   chunk   {"id": .., "offset": N}                         -> {"result": "<next piece>", "more": bool}
 *   close   {"handle": ..}                                  -> {}
 *   answer  {"ok": false, "error": "<a sentence a person can read>"}; an "unknown handle" error means the
 *           engine process restarted since the connection was opened: the client opens it again.
 *
 * A PUBLISHED CONTRACT: the verbs and fields may only grow. The models travel as kotlinx-serialization JSON
 * text (the same classes on both sides), so a model never has a second hand-written codec.
 */
object MountWire {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The verbs the "fs" method answers; the engine refuses anything else. */
    val VERBS: Set<String> = setOf("list", "download", "upload", "mkdir", "delete", "rename", "exec")

    /** What an engine that lost a connection (it restarted) answers; the client reopens on it. */
    const val UNKNOWN_HANDLE = "unknown handle"

    /**
     * The request that opens a connection. The ssh key and the known_hosts are FILES in the caller's private
     * storage, which another uid cannot read, so their text travels; [readText] returns a file's text, or null
     * when it does not exist (known_hosts before a first contact). The secret travels for this call only.
     */
    fun openRequest(spec: MountSpec, secret: MountSecret, knownHosts: File, readText: (String) -> String? = { p -> File(p).takeIf { it.isFile }?.readText() }): JSONObject {
        val req = JSONObject().put("spec", json.encodeToString(MountSpec.serializer(), spec))
            .put("password", secret.password ?: JSONObject.NULL).put("passphrase", secret.passphrase ?: JSONObject.NULL)
            .put("known_hosts", readText(knownHosts.path) ?: "")
        if (spec.keyPath.isNotBlank()) req.put("key", readText(spec.keyPath) ?: throw java.io.IOException("ssh key not found: ${spec.keyPath}"))
        return req
    }
}
