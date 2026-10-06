package com.diegonmarcos.superapp.ops;

/**
 * The ops engine's wire (Cloud-Lib-Ops-Engine.apk). One call = one request to Dagu: the engine
 * runs the HTTPS call in its own process, the CLIENT keeps the server URL and the bearer (they
 * are the caller's and arrive with every call; the engine stores neither).
 */
interface IOpsEngine {
    /**
     * Run [method] ("dagu_list" or "dagu_start") with the JSON [request] ({server, token, ...}).
     * Answers {"ok":true,...} or {"ok":false,"error":...}. Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
