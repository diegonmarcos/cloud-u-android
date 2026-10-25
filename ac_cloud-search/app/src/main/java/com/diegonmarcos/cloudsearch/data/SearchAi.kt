package com.diegonmarcos.cloudsearch.data

import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiHttp
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiOpenRouterBackend
import com.diegonmarcos.superapp.ai.AiPrefs
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.ai.AiToken
import com.diegonmarcos.superapp.ai.AiTokenSource

/**
 * Cloud Search as the host of libs:ai (#823, renamed from libs:search-page): the chat is the lib's
 * [AiStandardHost] over its two backends, on this app's own pieces. OpenRouter with the token read from the
 * fleet Account per send ([Account], never stored here); the fleet agent gateway (mesh only, no token) when an
 * agent is picked, at the address baked as BuildConfig.AGENT_GATEWAY_URL; the live model catalogue is
 * [Services.models] (cached, offline-aware); sessions are [Services.sessions]; the model, Web switch and agent
 * are [Services.prefs]; a URL opens in Cloud Browser ([Browser]). Requests go through [Services.http], the
 * app's one network seam, so the shell tests fake the network as before (that client hands over whole answers).
 */
object SearchAi {
    fun host(s: Services): AiHost {
        val cfg = AiConfig.load(s.app)
        val http = object : AiHttp {
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): AiHttp.Response =
                s.http.get(url, headers, timeoutMs).let { AiHttp.Response(it.code, it.body) }

            override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, onChunk: ((String) -> Unit)?): AiHttp.Response =
                s.http.post(url, headers, body, timeoutMs).let { AiHttp.Response(it.code, it.body) }
        }
        val prefs = object : AiPrefs {
            override var model: String
                get() = s.prefs.model
                set(v) { s.prefs.model = v }
            override var web: Boolean
                get() = s.prefs.web
                set(v) { s.prefs.web = v }
            override var agent: String
                get() = s.prefs.agent
                set(v) { s.prefs.agent = v }
        }
        val tokens = AiTokenSource { Account.token(s.app, cfg.accountProvider).let { AiToken(it.value, it.why) } }
        return AiStandardHost(
            cfg, s.sessions, prefs,
            direct = AiOpenRouterBackend(cfg, s.cfg.ai.title, tokens, http),
            gateway = AiGatewayBackend(cfg, BuildConfig.AGENT_GATEWAY_URL, http),
            agents = AiAgents.load(s.app),
            modelSource = { s.models.models().map { AiModel(it.id, it.name, it.nativeWeb, it.free) } },
            opener = { Browser.open(s.app, it) },
            design = AiDesign.load(s.app),
        )
    }
}
