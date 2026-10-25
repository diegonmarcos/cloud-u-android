package com.diegonmarcos.cloudtoolssmallllms.data

import com.diegonmarcos.superapp.ai.AiChatBackend
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiHttp
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiUrlHttp

/**
 * The Local chat backend: libs:ai's OpenAI-compatible chat against a Store runtime at [base] (a fleet Ollama serves
 * /v1/chat/completions). The picked model is sent by its own name, which is what the runtime holds it under. No key.
 */
class LocalChatBackend(cfg: AiConfig, private val base: String, http: AiHttp = AiUrlHttp) : AiChatBackend(cfg, http) {
    override val id: String get() = "local"
    override val openRouter: Boolean get() = false
    override fun url(req: AiRequest) = base.trimEnd('/') + cfg.gatewayChatPath
    override fun headers(req: AiRequest): Map<String, String> = mapOf("Content-Type" to "application/json")
    override fun model(req: AiRequest): String? = req.model
    override fun models(): List<AiModel> = emptyList()
}
