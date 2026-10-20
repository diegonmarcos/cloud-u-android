package com.diegonmarcos.cloudlib.rclone;

/**
 * The rclone engine's wire (Cloud-Lib-Rclone-Engine.apk). One call = one rclone run, or one step
 * of a job: the engine execs the pinned static rclone in its own process, the CLIENT keeps the
 * rclone.conf (it arrives with every call; the engine stores none of it).
 */
interface IRcloneEngine {
    /**
     * Run [method] ("info", "run", "out_chunk", "job_start", "job_poll" or "job_cancel") with the
     * JSON [request]. Answers {"ok":true,...} or {"ok":false,"error":...}. Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
