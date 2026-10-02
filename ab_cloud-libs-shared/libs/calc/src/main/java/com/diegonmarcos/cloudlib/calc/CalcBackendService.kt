package com.diegonmarcos.cloudlib.calc

import com.diegonmarcos.superapp.core.DataBackendService

/**
 * [CalcEngine] behind core's IDataBackend, shipped in Cloud-Lib-Calc.apk.
 *
 * A PUBLISHED CONTRACT: Cloud Calc (ac_cloud-calc CalcClient.kt) binds this by handshake and
 * compiles nothing of this module, so an edit here ships Cloud-Lib-Calc.apk alone.
 * [methodNames] may only grow; an answer that changes shape ships under a new method name and a
 * higher CONTRACT in the manifest. Held by the engine contract guard (K4: every method Cloud
 * Calc calls is listed here) and lib-apks test-engine-services.sh (E4: listed == answered).
 *
 *   info                                 engine, libqalculate version, definition counts, rates age
 *   eval        expr, options-json       {"ok","result","parsed","comparison","messages","ms"}
 *   plot        expr, xmin, xmax, steps  {"ok","x":[..],"y":[..]}
 *   complete    prefix, max              [{"name","title","kind","category"}]
 *   items       kind, category, max      same rows, one category of functions/variables/units
 *   ratesInfo                            {"sources":[{"url","file"}],"time"}
 *   fetchRates                           {"ok","fetched","failed","time"} — the only network call
 */
class CalcBackendService : DataBackendService() {

    private val engine by lazy { CalcEngine(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(
        "info", "eval", "plot", "complete", "items", "ratesInfo", "fetchRates",
    )

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        "info"       -> engine.info()
        "eval"       -> engine.eval(args.getOrNull(0).orEmpty(), args.getOrNull(1))
        "plot"       -> engine.plot(
            args.getOrNull(0).orEmpty(),
            args.getOrNull(1)?.toDoubleOrNull() ?: -10.0,
            args.getOrNull(2)?.toDoubleOrNull() ?: 10.0,
            args.getOrNull(3)?.toIntOrNull() ?: 200,
        )
        "complete"   -> engine.complete(args.getOrNull(0).orEmpty(), args.getOrNull(1)?.toIntOrNull() ?: 20)
        "items"      -> engine.items(
            args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty(), args.getOrNull(2)?.toIntOrNull() ?: 500)
        "ratesInfo"  -> engine.ratesInfo()
        "fetchRates" -> engine.fetchRates()
        else -> throw IllegalArgumentException("unknown method: $method")
    }
}
