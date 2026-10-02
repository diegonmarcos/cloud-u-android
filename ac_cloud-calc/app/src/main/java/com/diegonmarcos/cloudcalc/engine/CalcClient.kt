package com.diegonmarcos.cloudcalc.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONObject

/**
 * Cloud-Lib-Calc, bound by handshake (build.json::engines.calc; the NewsBridge shape). This app
 * compiles none of libqalculate: every calculation crosses to the engine's process.
 *
 * Call off the main thread — binding blocks.
 */
class CalcClient(context: Context) : CalcApi {
    private val ctx = context.applicationContext

    companion object {
        /** The engine CONTRACT meta-data key (libs/calc's manifest). */
        private const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
    }

    @Volatile private var client: DataBackendClient? = null

    /**
     * THE HANDSHAKE, BEFORE ANY BIND: resolve the declared action in the declared package with
     * its meta-data and read the CONTRACT it serves. Null when the engine is ready, else the
     * sentence that says what to do — "install" and "update" are different next steps.
     */
    fun check(): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.CALC_ENGINE_PACKAGE
        val needed = BuildConfig.CALC_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.CALC_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) "$pkg has no calc engine service — update it from Store ▸ Cloud Constellation ▸ Libs"
                   else "$pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
        return null
    }

    private fun ask(method: String, vararg args: String): String {
        val why = check()
        val c = client
        if (why != null || c == null) return JSONObject().put("ok", false).put("error", why ?: "the calc engine is not ready").toString()
        return c.call(method, *args)
    }

    override fun info(): String = ask("info")
    override fun eval(expr: String, options: String): String = ask("eval", expr, options)
    override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int): String =
        ask("plot", expr, xmin.toString(), xmax.toString(), steps.toString())
    override fun complete(prefix: String, max: Int): String = ask("complete", prefix, max.toString())
    override fun items(kind: String, category: String, max: Int): String = ask("items", kind, category, max.toString())
    override fun ratesInfo(): String = ask("ratesInfo")
    override fun fetchRates(): String = ask("fetchRates")
}
