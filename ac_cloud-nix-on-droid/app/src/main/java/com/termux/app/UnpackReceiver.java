package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.cloud.UnpackReport;
import com.termux.shared.logger.Logger;

/**
 * #1121 After this app is updated (MY_PACKAGE_REPLACED, whoever installed it), unpack its bundled
 * files through {@link UnpackService}, a short foreground service: no activity, no UI, so the
 * app never has to be opened. Same file in ac_cloud-termux and ac_cloud-nix-on-droid.
 */
public class UnpackReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "UnpackReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
            || !UnpackReport.runsOn(intent.getAction())) return;
        try {
            UnpackService.start(context.getApplicationContext(), null);
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not start the unpack service", e);
        }
    }
}
