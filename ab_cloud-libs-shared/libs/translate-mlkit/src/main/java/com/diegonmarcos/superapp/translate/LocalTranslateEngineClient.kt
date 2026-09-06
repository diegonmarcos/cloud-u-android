package com.diegonmarcos.superapp.translate

import android.content.Context

/**
 * In-process ML Kit engine client. Registered by the superapp and by
 * cloud-keyboard-libs; cloud-keyboard (standalone APK) uses
 * AidlTranslateEngineClient instead so ML Kit is not bundled there.
 *
 * Reply shape mirrors the AIDL one: {sourceTag, text[, errorMessage]} — the
 * message is what the bar shows, so it must say WHY (see TranslateEngine).
 */
class LocalTranslateEngineClient(private val context: Context) : TranslateEngineClient {

    override fun translate(text: String, targetTag: String): Array<String> =
        try {
            TranslateEngine.translateBlocking(context, text, targetTag)
        } catch (e: Exception) {
            failed(e)
        }

    override fun translateFrom(text: String, sourceTag: String, targetTag: String): Array<String> =
        try {
            TranslateEngine.translateBlocking(context, text, sourceTag, targetTag)
        } catch (e: Exception) {
            failed(e)
        }

    private fun failed(e: Exception) = arrayOf("und", "", e.message?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName)

    override fun supportedLanguages(): List<String> =
        TranslateEngine.supportedLanguageTags()

    // In-process — always ready, no bind step to fail.
    override fun isConnected(): Boolean = true
}
