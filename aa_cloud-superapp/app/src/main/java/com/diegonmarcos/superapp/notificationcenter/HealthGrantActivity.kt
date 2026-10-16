package com.diegonmarcos.superapp.notificationcenter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.diegonmarcos.superapp.health.HealthConnectGateway

/**
 * Runs Health Connect's own permission dialog (PermissionController.createRequestPermissionResultContract)
 * for the Health badge's steps / distance / calories reads. A notification or a settings row cannot launch
 * that contract by itself; this transparent activity does and finishes. If the dialog cannot be shown
 * (Health Connect missing or already refusing), the Health Connect settings page opens instead.
 */
class HealthGrantActivity : ComponentActivity() {
    private val ask = registerForActivityResult(HealthConnectGateway.requestPermissionsContract()) {
        // The badge re-reads on its own cadence; poke it so the new grant shows now.
        runCatching { startService(Intent(this, HealthBadgeService::class.java)) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        runCatching { ask.launch(HealthConnectGateway.BADGE_PERMISSIONS) }.onFailure {
            sequenceOf(
                Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
                Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
            ).any { i -> runCatching { startActivity(i) }.isSuccess }
            finish()
        }
    }
}
