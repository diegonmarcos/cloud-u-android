package com.termux.cloud;

import android.os.Build;

import com.diegonmarcos.cloudlib.sysdns.SystemDnsBridge;
import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;

import java.io.IOException;

/**
 * #741 where the rootfs shell's DNS goes. enter.sh runs proot with -p, and the tree's
 * resolv.conf names 127.0.0.1, so every lookup in the shell lands on 127.0.0.1:
 * {@link BuildConfig#CLOUD_DNS_BRIDGE_PORT}; this starts the fleet's one bridge there
 * (libs/sysdns SystemDnsBridge), which answers each query with Android's own resolver for this
 * app, i.e. with whatever the SuperApp's Configs > Mesh > DNS applies.
 *
 * Started with the terminal service and never stopped: it costs two idle sockets. Loopback is
 * shared by every app, so a second fleet terminal finds the port taken; that one's shells are
 * then answered by the first, which resolves the same way, and the log says so.
 */
public final class CloudDnsBridge {

    private static final String LOG_TAG = "CloudDnsBridge";
    private static SystemDnsBridge bridge;
    /** #794 why there is no bridge here, for /api/sysdns/state; null while none was tried. */
    private static String whyNot;

    private CloudDnsBridge() { }

    /** #889 while the shells' port is held elsewhere, one bind attempt this often until it is ours. */
    private static final long RETRY_MS = 3000;
    private static Thread rebind;

    public static synchronized void start() {
        if (bridge != null || rebind != null) return;
        if (Build.VERSION.SDK_INT < 29) {
            // ponytail: no raw system resolver below Android 10 (DnsResolver is API 29); the shell
            // then has no DNS rather than a server of its own. Add an InetAddress-backed upstream
            // if a pre-10 phone ever joins the fleet.
            whyNot = "Android " + Build.VERSION.SDK_INT + " has no raw system resolver: shell lookups will fail";
            Logger.logError(LOG_TAG, whyNot);
            return;
        }
        if (bind()) return;
        Logger.logWarn(LOG_TAG, whyNot);
        // #889 the holder may be the other terminal (fine: it resolves the same way) or a process
        // that is cached, frozen or gone -- then every lookup in this shell hangs. This terminal is a
        // foreground service: keep trying, and own the port the moment it frees.
        rebind = new Thread(() -> {
            while (true) {
                try { Thread.sleep(RETRY_MS); } catch (InterruptedException e) { return; }
                synchronized (CloudDnsBridge.class) {
                    if (bind()) { rebind = null; return; }
                }
            }
        }, "sysdns-rebind");
        rebind.setDaemon(true);
        rebind.start();
    }

    /** One bind on the shells' port: true when this terminal now answers it. */
    private static boolean bind() {
        try {
            bridge = new SystemDnsBridge(BuildConfig.CLOUD_DNS_BRIDGE_PORT, SystemDnsBridge.android(),
                line -> Logger.logInfo(LOG_TAG, line));
            whyNot = null;
            return true;
        } catch (IOException e) {
            whyNot = "127.0.0.1:" + BuildConfig.CLOUD_DNS_BRIDGE_PORT
                + " is taken (" + e.getMessage() + "): another fleet terminal answers this shell's DNS;"
                + " retrying every " + (RETRY_MS / 1000) + " s to take it over when it frees (#889)";
            return false;
        }
    }

    /** #794 the body of /api/sysdns/state: this terminal's bridge, or why it has none. */
    public static synchronized String state() {
        if (bridge != null) return bridge.stateJson();
        return SystemDnsBridge.notListeningJson(BuildConfig.CLOUD_DNS_BRIDGE_PORT,
            whyNot != null ? whyNot : "not started yet: the terminal service starts it");
    }
}
