package com.diegonmarcos.cloudlib.mounts;

/**
 * The mounts engine's wire (Cloud-Lib-Mounts-Engine.apk). One call = one step on one connection: the
 * engine opens ftp / sftp / ssh / webdav connections in its own process and keeps them between calls by
 * handle; the CLIENT keeps the mount list, the passwords and passphrases, the ssh key and the known_hosts
 * files (they arrive with the call that opens a connection; the engine stores none of them).
 */
interface IMountsEngine {
    /**
     * Run [method] ("info", "open", "fs", "chunk" or "close") with the JSON [request] (see MountWire).
     * Answers {"ok":true,...} or {"ok":false,"error":...}. Never throws across the binder.
     */
    String call(String method, String request);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
