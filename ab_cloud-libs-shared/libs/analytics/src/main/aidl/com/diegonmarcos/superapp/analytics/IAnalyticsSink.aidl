package com.diegonmarcos.superapp.analytics;

/**
 * The analytics sink engine's wire (Cloud-Lib-Analytics-Sink.apk). One call = one event
 * delivered to one backend: the engine runs the HTTP POST in its own process, the CLIENT
 * keeps the queue, the consent flag and the visitor id (they are per app).
 */
interface IAnalyticsSink {
    /**
     * Deliver one event to the sink named by [method] ("umami" or "matomo"); [request] is the
     * JSON the engine builds the POST from. Answers {"ok":true} or {"ok":false,"error":...}.
     * Never throws across the binder.
     */
    String send(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
