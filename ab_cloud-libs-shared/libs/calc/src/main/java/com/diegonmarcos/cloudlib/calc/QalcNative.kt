package com.diegonmarcos.cloudlib.calc

/**
 * The JNI surface of libqalc.so (native/qalc_jni.cc registers these by name in JNI_OnLoad,
 * against THIS class's binary name — moving or renaming it breaks the load, loudly).
 * Strings cross as UTF-8 bytes; every answer is one JSON document from native/qalc_core.cc.
 *
 * libqalculate keeps ONE global Calculator and is not thread-safe, so [CalcEngine] serialises
 * every call; nothing else may call these.
 */
internal object QalcNative {
    init { System.loadLibrary(BuildConfig.QALC_LIBRARY) }

    @JvmStatic external fun init(userDir: ByteArray): ByteArray
    @JvmStatic external fun eval(
        expr: ByteArray, inBase: Int, outBase: Int, precision: Int,
        angle: Int, approx: Int, mixedUnits: Boolean, unicode: Boolean, timeoutMs: Int,
    ): ByteArray
    @JvmStatic external fun plot(expr: ByteArray, xmin: Double, xmax: Double, steps: Int, timeoutMs: Int): ByteArray
    @JvmStatic external fun complete(prefix: ByteArray, max: Int): ByteArray
    @JvmStatic external fun items(kind: ByteArray, category: ByteArray, max: Int): ByteArray
    @JvmStatic external fun ratesSources(): ByteArray
    @JvmStatic external fun reloadRates(): ByteArray
    @JvmStatic external fun info(): ByteArray
}
