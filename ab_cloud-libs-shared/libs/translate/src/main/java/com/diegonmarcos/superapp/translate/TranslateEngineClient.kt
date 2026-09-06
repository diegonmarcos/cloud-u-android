package com.diegonmarcos.superapp.translate

/**
 * Swappable translate-engine interface.
 *
 * Implementations:
 *  - LocalTranslateEngineClient (libs:translate-mlkit) — in-process ML Kit.
 *    Registered by the superapp and cloud-keyboard-libs.
 *  - AidlTranslateEngineClient (cloud-keyboard app) — binds the companion
 *    cloud-keyboard-libs service. Registered by the standalone keyboard APK
 *    so ML Kit is not bundled there.
 */
interface TranslateEngineClient {
    /** Auto-detects the source. Returns [detectedTag, translatedText]; detectedTag "und" = detection failed. */
    fun translate(text: String, targetTag: String): Array<String>

    /**
     * Translate with an EXPLICIT source — no detection at all. Returns
     * [sourceTag, translatedText]. Detection was the #1 reason the live bar
     * came back empty: ML Kit's identifier wants ≥0.5 confidence, which one to
     * three words rarely reach, so every keystroke early in a sentence produced
     * "und". Default delegates to [translate] so an engine that predates this
     * method keeps working; real engines override it.
     */
    fun translateFrom(text: String, sourceTag: String, targetTag: String): Array<String> = translate(text, targetTag)

    fun supportedLanguages(): List<String>

    /**
     * True when the engine is actually able to translate right now — for
     * AidlTranslateEngineClient this reflects the real AIDL bind state, NOT
     * just whether the client object exists (a client can be constructed and
     * registered successfully while its underlying service connection is
     * still pending, failed, or was silently refused). Settings/diagnostic
     * screens must check this, not `client != null`, to report honest status.
     */
    fun isConnected(): Boolean
}
