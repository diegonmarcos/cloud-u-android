package com.diegonmarcos.superapp.appstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * #1121 Test entry: makes the Store (a package of its own) start a terminal's unpack exactly as it does
 * after an install, so an emulator test can prove the cross-process hand-off (the Store's ResultReceiver
 * has to unmarshal in the TERMINAL's classloader). adb shell holds DUMP, no other app does, so only
 * `adb shell am broadcast -n <store>/...UnpackTestReceiver -a [ACTION] --es pkg <terminal>` reaches it.
 */
class UnpackTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra("pkg") ?: return
        // Keep the process (and so the reply binder) alive until the terminal has answered.
        val pending = goAsync()
        UnpackAfterInstall.run(context, pkg)
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, HOLD_MS)
    }

    companion object {
        const val ACTION = "com.diegonmarcos.cloud.action.TEST_UNPACK"
        private const val HOLD_MS = 120_000L
    }
}
