package com.diegonmarcos.cloudcalc.engine

/**
 * What the screens need from the engine. [CalcClient] is the real one (Cloud-Lib-Calc over
 * IDataBackend); the Robolectric smoke test supplies a fake, so every mode composes without an
 * engine APK. Every answer is the engine's own JSON, or {"error": why} when it is not ready.
 */
interface CalcApi {
    fun info(): String
    fun eval(expr: String, options: String): String
    fun plot(expr: String, xmin: Double, xmax: Double, steps: Int): String
    fun complete(prefix: String, max: Int): String
    fun items(kind: String, category: String, max: Int): String
    fun ratesInfo(): String
    fun fetchRates(): String
}
