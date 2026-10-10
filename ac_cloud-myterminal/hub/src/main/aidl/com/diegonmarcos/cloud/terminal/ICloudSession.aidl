// The fleet's terminal-session contract. The SAME file, byte for byte, lives in
// ac_cloud-termux, ac_cloud-nix-on-droid (the servers) and ac_cloud-myterminal
// (the client): the interface descriptor is this package + name, and a client
// whose copy differs talks to a server that reads its parcels differently.
// ac_cloud-myterminal/test/test-terminal-session.sh fails when the three drift.
//
// APPEND ONLY. AIDL numbers the methods in declaration order, and an older
// server answers a code it does not know with an empty reply — which reads as
// version() == 0, the "too old" signal the client acts on. Reordering or
// removing one silently rewires every installed peer.
package com.diegonmarcos.cloud.terminal;

interface ICloudSession {
    /** Contract version; 1 = this file. An empty reply (an older build) reads as 0. */
    int version();

    /** Bootstrap the env if it never was (headless) and start its foreground
     *  service. ok=true, or ok=false + error naming what is missing. */
    Bundle prepare();

    /** A login shell in a fresh PTY; the master end comes back. null on
     *  failure, with info.error saying why; info.pid on success. [id] is the
     *  caller's own handle, scoped to its uid. */
    ParcelFileDescriptor openPty(String id, int cols, int rows, String cwd, out Bundle info);

    void resize(String id, int cols, int rows);

    /** Ends the shell (SIGKILL, as closing a session in the app does). */
    void close(String id);

    /** Runs a POSIX sh script inside the env (through its login, so in the
     *  same root a session sees). stdout, stderr, exit, timed_out. */
    Bundle exec(String script, int timeoutMs);
}
