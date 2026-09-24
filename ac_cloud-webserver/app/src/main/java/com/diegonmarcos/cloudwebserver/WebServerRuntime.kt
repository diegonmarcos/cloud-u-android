package com.diegonmarcos.cloudwebserver

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the baked rootfs + binary and launches the binary under proot.
 *
 * Every path, flag and library directory comes from the runtime asset that
 * app/build.gradle::bakeWebserver derived from the binary's own ELF headers
 * (#288 Route D). This class knows proot's environment variables and how to
 * unpack a nix-on-droid bootstrap (entries + SYMLINKS.txt), nothing else.
 */
class WebServerRuntime(private val ctx: Context) {
    val spec = JSONObject(ctx.assets.open(BuildConfig.RUNTIME_ASSET).bufferedReader().use { it.readText() })
    val port = spec.getInt("port")
    val serveRoot: String = spec.getString("serve_root")
    val url = "http://127.0.0.1:$port/"
    private val assets = spec.getJSONObject("assets")
    private val root = File(ctx.filesDir, "rootfs")

    /** Extract once per (rootfs, binary) pair; the stamp is both sha256 pins. */
    private fun install() {
        val stamp = File(root, ".stamp")
        if (stamp.isFile && stamp.readText() == spec.getString("stamp")) return
        root.deleteRecursively()
        root.mkdirs()
        var symlinks = ""
        ZipInputStream(ctx.assets.open(assets.getString("rootfs"))).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                require(!e.name.contains("..")) { "rootfs entry escapes: ${e.name}" }
                val out = File(root, e.name)
                when {
                    e.name == "SYMLINKS.txt" -> symlinks = zip.bufferedReader().readText()
                    e.isDirectory -> out.mkdirs()
                    else -> {
                        out.parentFile!!.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        Os.chmod(out.path, "700".toInt(8))
                    }
                }
            }
        }
        for (line in symlinks.lines()) {
            val (target, link) = line.split("←").takeIf { it.size == 2 } ?: continue
            File(root, link).parentFile!!.mkdirs()
            Os.symlink(target, File(root, link).path)
        }
        val binary = File(root, assets.getString("binary"))
        ctx.assets.open(assets.getString("binary")).use { i -> binary.outputStream().use { i.copyTo(it) } }
        Os.chmod(binary.path, "700".toInt(8))
        stamp.writeText(spec.getString("stamp"))
    }

    fun start(log: File): Process {
        install()
        val cmd = mutableListOf(File(root, spec.getString("launcher")).path)
        val flags = spec.getJSONArray("launcher_flags")
        for (i in 0 until flags.length()) cmd += flags.getString(i)
        val binds = spec.getJSONArray("binds")
        for (i in 0 until binds.length()) {
            val b = binds.getJSONObject(i)
            cmd += "-b"
            cmd += File(root, b.getString("host")).path + ":" + b.getString("guest")
        }
        cmd += listOf(File(root, assets.getString("binary")).path, port.toString(), serveRoot)

        val libs = spec.getJSONArray("library_path")
        val home = File(ctx.filesDir, "home").apply { mkdirs() }
        return ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["LD_LIBRARY_PATH"] = (0 until libs.length()).joinToString(":") { libs.getString(it) }
            environment()["HOME"] = home.path
            environment()["PROOT_TMP_DIR"] = File(ctx.cacheDir, "proot").apply { mkdirs() }.path
            environment()["PROOT_L2S_DIR"] = File(ctx.filesDir, ".l2s").apply { mkdirs() }.path
        }.start()
    }
}
