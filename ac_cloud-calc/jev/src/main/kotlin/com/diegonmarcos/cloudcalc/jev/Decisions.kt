package com.diegonmarcos.cloudcalc.jev

import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject

/**
 * The Jev section's side of the Decisions API. The protocol itself — the POST, its failure
 * reasons, the probabilities, the token mask — is libs:decisions (#772 lifted it out of this
 * module so the image engine speaks the same client); this file only binds it to
 * build.json::jev: the endpoint and timeout come from the declaration, the questions from its
 * [JevConfig.Question]s. No URL is spelled here (test/test-calc-shell.sh C6).
 */
fun Decisions.call(cfg: JevConfig, http: Http, token: String?, model: String, state: Any, questions: Map<String, JevConfig.Question>, clock: () -> Long = System::currentTimeMillis): Decision =
    post(cfg.endpoint, cfg.timeoutMs, http, token, model, state, JSONObject().apply { questions.forEach { (k, q) -> put(k, q.wire()) } }, clock)
