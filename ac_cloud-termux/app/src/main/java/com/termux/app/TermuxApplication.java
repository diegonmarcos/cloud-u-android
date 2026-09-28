package com.termux.app;

import android.app.Application;

import com.termux.cloud.CloudTermuxProperties;
import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;


public class TermuxApplication extends Application {
    public void onCreate() {
        super.onCreate();

        // #620 — write allow-external-apps=true into ~/.termux/termux.properties on
        // every launch and every boot, before anything in this process reads that
        // file. Without it RunCommandService refuses the boot runner's intent, and
        // nothing in this app ever wrote the property (it only read it).
        CloudTermuxProperties.ensureAllowExternalApps();

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

