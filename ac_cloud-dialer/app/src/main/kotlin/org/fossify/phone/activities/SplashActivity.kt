package org.fossify.phone.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Launcher trampoline. It deliberately does NOT extend commons' BaseSplashActivity: that class
 * runs commons' repackaging check ("you are using a fake version of the app") on every start.
 * Cloud Dialer is an intentional fork under its own applicationId, so there is nothing to check.
 */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
