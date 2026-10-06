package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.texttools.TextTools
import com.diegonmarcos.superapp.texttools.TextToolsClient

/**
 * #886 the real [PageTranslate.TextToolsPort]: the fleet's text-tools BINDER ([TextToolsClient]), i.e.
 * Cloud Writer / Cloud Keyboard, which own the translation library, the AI routing and the provider
 * key. Provider and model are sent EMPTY, which means "whatever the serving app has chosen" — this app
 * holds no key and makes no model decision of its own (cloud-mail's reference shape for "routes, but
 * holds no key"). Every call BLOCKS: use it off the main thread.
 */
class TextToolsClientPort(private val c: TextToolsClient) : PageTranslate.TextToolsPort {
    override fun installed() = c.isServingAppInstalled()
    override fun translate(text: String, tag: String): TextTools.Result = c.translate(text, tag)
    override fun translateLanguages(): List<String> = c.translateLanguages()
    override fun enhanceWith(text: String, systemPrompt: String): TextTools.Result = c.enhanceWith(text, systemPrompt, "", "")
    override fun summariseWith(text: String, systemPrompt: String): TextTools.Result = c.summariseWith(text, systemPrompt, false, "", "")
    override fun providerLabel(): String? = c.enhanceProviderLabel()
}
