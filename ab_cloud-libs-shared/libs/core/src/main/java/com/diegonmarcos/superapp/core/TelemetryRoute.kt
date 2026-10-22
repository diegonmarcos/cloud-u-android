package com.diegonmarcos.superapp.core

import java.io.File

/**
 * The pure half of crash/log telemetry: WHERE an app's events go and the offline queue they wait in.
 * c3-infra-api serves one declared route per app, `/apk/<name>/logs` (POST ingest, GET read); the mesh origin
 * (`c3-infra-api.app`, no auth) is tried first, then the public origin (c3-pvt, the fleet bearer). Both origins come
 * from the one fleet declaration (ac_cloud-account/build.json::fleet_endpoints, baked as BuildConfig by build.gradle),
 * nothing is typed here. No Android in this file, so the JVM suite runs it as it is.
 */
object TelemetryRoute {
    const val KIND = "apk"

    fun path(appId: String): String = "/$KIND/$appId/logs"

    /** The URLs to try, mesh first; an origin the declaration does not carry is left out. */
    fun urls(meshOrigin: String, publicOrigin: String, appId: String): List<String> =
        listOf(meshOrigin, publicOrigin).filter { it.isNotBlank() }.map { it.trimEnd('/') + path(appId) }
}

/** Events that could not be sent (no network, mesh down) wait on disk and go out on the next start. Oldest dropped first. */
object TelemetryQueue {
    const val MAX_ITEMS = 10
    const val MAX_ITEM_BYTES = 400_000

    /** Stores [payload]; false when it is too large to keep. Keeps the newest [MAX_ITEMS]. */
    fun enqueue(dir: File, payload: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (payload.length > MAX_ITEM_BYTES) return false
        dir.mkdirs()
        File(dir, "%016d-%04d.json".format(nowMs, Math.floorMod(System.nanoTime(), 10_000L).toInt())).writeText(payload, Charsets.UTF_8)
        files(dir).dropLast(MAX_ITEMS).forEach { it.delete() }
        return true
    }

    fun files(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    /** Sends the oldest first and deletes each one [send] accepts; stops at the first refusal (the network is still down). Returns how many went. */
    fun drain(dir: File, send: (String) -> Boolean): Int {
        var sent = 0
        for (f in files(dir)) {
            val ok = runCatching { send(f.readText(Charsets.UTF_8)) }.getOrDefault(false)
            if (!ok) break
            f.delete()
            sent++
        }
        return sent
    }
}
