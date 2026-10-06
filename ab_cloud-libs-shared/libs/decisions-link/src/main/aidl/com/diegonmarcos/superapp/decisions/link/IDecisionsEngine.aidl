package com.diegonmarcos.superapp.decisions.link;

/**
 * The decisions engine's wire (Cloud-Lib-Decisions-Engine.apk). One call = one JSON request and one JSON
 * answer. NOTHING IN HERE CARRIES A CREDENTIAL: the engine holds the OpenRouter token in its own process
 * and learns WHO is asking from the binder, not from the request.
 */
interface IDecisionsEngine {
    /**
     * Run [method] ("decide", "status" or "consent") with the JSON [request]. Answers {"ok":true,...} or
     * {"ok":false,"reason":...}; a reason is "no opinion" and the caller keeps today's behaviour.
     * Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
