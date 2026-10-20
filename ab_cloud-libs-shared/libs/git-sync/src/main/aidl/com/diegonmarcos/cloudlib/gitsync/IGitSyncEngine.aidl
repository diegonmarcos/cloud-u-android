package com.diegonmarcos.cloudlib.gitsync;

/**
 * The git sync engine's wire (Cloud-Lib-Git-Sync-Engine.apk). One call = one git verb on one repository:
 * the engine runs JGit in its own process; the CLIENT keeps the registry, the credentials and the ssh key
 * and known_hosts files (they arrive with every call, for that call; the engine stores none of them).
 */
interface IGitSyncEngine {
    /**
     * Run [method] ("info", "git" or "chunk") with the JSON [request] (see GitWire). Answers
     * {"ok":true,...} or {"ok":false,"error":...}. Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
