// SPDX-License-Identifier: GPL-3.0-only
package com.diegonmarcos.superapp.texttools

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.translate.TranslateEngines
import com.diegonmarcos.superapp.translate.TranslatePrefs
import com.diegonmarcos.superapp.translate.Translator
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.TextEnhancer
import helium314.keyboard.latin.utils.Log

/**
 * Serves [ITextTools] out of the app that owns the Text tools.
 *
 * THIS IS THE WHOLE POINT OF THE MODULE. The two engines and their settings stay in
 * one process — this one. A sibling app asking for a rewrite does not link AiRouter,
 * does not read the routing prefs, and above all never receives the provider key: the
 * key is read here, spent here on one HTTPS request, and only the resulting TEXT
 * crosses the binder. There is no second copy of the credential path because there is
 * no second holder of the credential.
 *
 * It also means there is exactly ONE store of these settings. A caller cannot pick a
 * different model or a different translation target from the one AI Model Routing and
 * Translation settings hold, because it never gets to choose — it hands over text.
 *
 * ROUTING, AND IT MUST NOT BE CROSSED:
 *   [enhance]   -> AiRouter / TextEnhancer  — the OpenRouter-shape provider.
 *   [summarise] -> AiRouter                 — the SAME provider, a different prompt set.
 *   [translate] -> Translator / TranslateEngines — the translation library.
 * The LLM pair and the translator may never reach each other's engine. They cost different money,
 * they answer differently, and a caller cannot tell from a reply which one produced it.
 * [enhance] and [summarise] deliberately DO share an engine: they are one feature's plumbing asked
 * two questions, and a second copy of the routing and credential path for the second question is
 * the duplication this whole module exists to prevent.
 *
 * Access is decided entirely by the manifest: the service is exported under
 * [TextTools.PERMISSION], declared `signature`, so the platform turns away any caller
 * not signed with the Cloud key before a line of this class runs. No uid check here —
 * re-implementing what the package manager already enforces, worse, is how those
 * checks come to disagree.
 *
 * Binder transactions arrive on a pool thread, never the main one, which is what makes
 * calling the blocking engines directly correct here.
 */
class TextToolsService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ITextTools.Stub() {

        override fun enhance(text: String?, styleId: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to enhance")
            val style = if (styleId.isNullOrEmpty()) {
                // The style, tone, length and output language pinned in Text Enhancements —
                // the same prompt the ENHANCE toolbar key builds, so the two surfaces cannot
                // produce different rewrites from the same settings.
                AiRouter.enhanceStyle(this@TextToolsService)
            } else {
                AiRouter.styleById(styleId)
            }
            return try {
                // rewrite(), not complete(): it splits text past the provider's input budget
                // on paragraph, then line, then sentence boundaries and rejoins on the
                // whitespace it cut at. An email body is routinely longer than one request,
                // which is the case the keyboard's short buffer rarely hit.
                ok(TextEnhancer.rewrite(this@TextToolsService, style, body))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "enhance failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun translate(text: String?, targetTag: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to translate")
            val result = try {
                Translator.translateNow(this@TextToolsService, body, targetTag.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "translate failed", e)
                return failed(e.message ?: e.javaClass.simpleName)
            }
            // Translator already turns every failure into a readable reason rather than a
            // null; pass that through instead of inventing a second wording for it.
            return result.text?.let { ok(it) } ?: failed(result.error ?: "Translate failed")
        }

        /**
         * "AI Resume" / "Text Resume" — SUMMARISE this message. The owner's product name, kept
         * exactly; it is not a curriculum vitae and it does not resume anything.
         *
         * THE SAME ENGINE AS [enhance], ON PURPOSE. Same provider, same key, same model, same
         * timeout, same error wording — the routing, credential and progress machinery is not
         * copied for a third feature, it is called with a different prompt set. Only two things
         * differ, and both are deliberate:
         *
         *  - the prompt comes from keyboard_ai.summaries via AiRouter.summaryStyle, not from the
         *    Enhance styles. Mixing the two menus would offer "improve this" to a feature that
         *    promises "shorten this".
         *  - it goes through AiRouter.summarise rather than TextEnhancer.rewrite. rewrite() splits
         *    input past the provider's budget and rejoins the answers, which for a rewrite is right
         *    and for a summary is exactly wrong: it produces a summary PER PIECE, concatenated, and
         *    a "summary" longer than the mail it was made from.
         *
         * NOTHING OF THE SUMMARY IS DECIDED HERE. The budget, the truncation note and the check
         * that a reply asked for bullets came back as bullets all live in AiRouter.summarise, which
         * the keyboard's own Text Resume bar also calls directly. That is what makes cloud-mail's
         * AI Resume and the keyboard's Text Resume the same feature rather than two features that
         * agree today: this method contributes the binder, and not one rule about summarising.
         */
        override fun summarise(text: String?, summaryId: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to summarise")
            val style = if (summaryId.isNullOrEmpty()) {
                AiRouter.summaryStyle(this@TextToolsService)
            } else {
                AiRouter.summaryById(summaryId)
            }
            return try {
                ok(AiRouter.summarise(this@TextToolsService, style, body))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "summarise failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun enhanceProviderLabel(): String =
            AiRouter.provider(this@TextToolsService).label

        override fun translateLanguages(): List<String> =
            TranslateEngines.client?.supportedLanguages().orEmpty().ifEmpty { TranslatePrefs.FALLBACK_LANGS }
    }

    private companion object {
        const val TAG = "TextToolsService"

        /** Slot 0 carries the result and slot 1 stays empty — see [ITextTools]. */
        fun ok(text: String) = arrayOf(text, "")

        /** Slot 0 stays empty and slot 1 carries the reason; never squash one into the other. */
        fun failed(reason: String) = arrayOf("", reason)
    }
}
