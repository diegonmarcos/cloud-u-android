package com.termux.app;

import android.content.Context;

import com.termux.BuildConfig;
import com.termux.cloud.MeshSshd;
import com.termux.shared.logger.Logger;

/** BOOT_COMPLETED: bring the terminal up as a foreground service and start the mesh sshd. */
public final class MeshSshdBoot {
    private static final String LOG_TAG = "MeshSshdBoot";
    private MeshSshdBoot() {}

    public static void onBoot(Context context) {
        if (!BuildConfig.CLOUD_MESH_SSHD_ON_BOOT) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String why = TerminalDebugApi.prepare(app); // bootstrap + startForegroundService(TermuxService)
            if (why != null) { Logger.logError(LOG_TAG, "terminal not ready: " + why); return; }
            try {
                String script = MeshSshd.script(BuildConfig.CLOUD_MESH_SSHD_PORT, BuildConfig.CLOUD_MESH_SSHD_BIND.split(","));
                Logger.logInfo(LOG_TAG, TerminalDebugApi.run(app, "sh -s", script, 300_000).toString());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "mesh sshd did not start", e);
            }
        }, "mesh-sshd-boot").start();
    }
}
