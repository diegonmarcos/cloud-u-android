// JNI marshalling for qalc_core, bound with RegisterNatives so the one name
// that ties this .so to Kotlin is QALC_JNI_CLASS below; it must match
// QalcNative.kt (com.diegonmarcos.cloudlib.calc.QalcNative, an object whose
// functions are @JvmStatic external). Strings cross as UTF-8 byte arrays,
// never jstring: NewStringUTF takes MODIFIED UTF-8 and aborts on a 4-byte
// sequence, and a result is free to contain one.
#include <jni.h>

#include <string>

#include "qalc_core.h"

#define QALC_JNI_CLASS "com/diegonmarcos/cloudlib/calc/QalcNative"

namespace {

std::string str(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    if (n) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray bytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

jbyteArray j_init(JNIEnv *env, jclass, jbyteArray dir) { return bytes(env, qcore::init(str(env, dir))); }

jbyteArray j_eval(JNIEnv *env, jclass, jbyteArray expr, jint in_base, jint out_base, jint precision,
                  jint angle, jboolean approximate, jboolean unicode, jint timeout_ms) {
    qcore::EvalOpts o;
    o.in_base = in_base;
    o.out_base = out_base;
    o.precision = precision;
    o.angle = angle;
    o.approximate = approximate == JNI_TRUE;
    o.unicode = unicode == JNI_TRUE;
    o.timeout_ms = timeout_ms;
    return bytes(env, qcore::eval(str(env, expr), o));
}

jbyteArray j_plot(JNIEnv *env, jclass, jbyteArray expr, jdouble xmin, jdouble xmax, jint steps, jint timeout_ms) {
    return bytes(env, qcore::plot(str(env, expr), xmin, xmax, steps, timeout_ms));
}

jbyteArray j_complete(JNIEnv *env, jclass, jbyteArray prefix, jint max) {
    return bytes(env, qcore::complete(str(env, prefix), max));
}

jbyteArray j_items(JNIEnv *env, jclass, jbyteArray kind, jbyteArray category, jint max) {
    return bytes(env, qcore::items(str(env, kind), str(env, category), max));
}

jbyteArray j_rates_sources(JNIEnv *env, jclass) { return bytes(env, qcore::rates_sources()); }
jbyteArray j_reload_rates(JNIEnv *env, jclass) { return bytes(env, qcore::reload_rates()); }
jbyteArray j_info(JNIEnv *env, jclass) { return bytes(env, qcore::info()); }

const JNINativeMethod kMethods[] = {
    {"init", "([B)[B", (void *) j_init},
    {"eval", "([BIIIIZZI)[B", (void *) j_eval},
    {"plot", "([BDDII)[B", (void *) j_plot},
    {"complete", "([BI)[B", (void *) j_complete},
    {"items", "([B[BI)[B", (void *) j_items},
    {"ratesSources", "()[B", (void *) j_rates_sources},
    {"reloadRates", "()[B", (void *) j_reload_rates},
    {"info", "()[B", (void *) j_info},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = env->FindClass(QALC_JNI_CLASS);
    if (!c) return JNI_ERR;
    if (env->RegisterNatives(c, kMethods, sizeof kMethods / sizeof kMethods[0]) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
