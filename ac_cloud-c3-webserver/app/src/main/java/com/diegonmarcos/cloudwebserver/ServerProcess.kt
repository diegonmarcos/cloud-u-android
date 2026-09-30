package com.diegonmarcos.cloudwebserver

import android.content.Context
import java.io.File

/**
 * Execs the baked Rust server. The binary lives where the installer put it,
 * applicationInfo.nativeLibraryDir/<BuildConfig.SERVER_JNI_NAME>, because that
 * is the one place an app may exec from on API 29+ (W^X refuses anything the
 * app wrote into its own data directory). It is a static musl executable: no
 * loader, no rootfs, no proot in front of it. Port and root come from
 * build.json::runtime through BuildConfig, so this class knows no path and no
 * number of its own.
 */
class ServerProcess(private val ctx: Context) {
    val port: Int = BuildConfig.SERVER_PORT
    val serveRoot: String = BuildConfig.SERVE_ROOT
    val url = "http://127.0.0.1:$port/"
    private val binary = File(ctx.applicationInfo.nativeLibraryDir, BuildConfig.SERVER_JNI_NAME)

    fun start(log: File): Process {
        check(binary.isFile) { "server binary missing: ${binary.absolutePath} — this APK was packaged without app/build.gradle::stageServer" }
        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        return ProcessBuilder(binary.absolutePath, port.toString(), serveRoot)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply {
                environment()["HOME"] = home.path
                environment()["TMPDIR"] = ctx.cacheDir.apply { mkdirs() }.path
            }
            .start()
    }
}
