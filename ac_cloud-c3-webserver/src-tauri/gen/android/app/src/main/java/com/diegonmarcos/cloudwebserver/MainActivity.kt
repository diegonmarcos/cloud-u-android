package com.diegonmarcos.cloudwebserver

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // The servers outlive this screen: promote them to the keep-alive foreground service (idempotent).
    startForegroundService(Intent(this, KeepAliveService::class.java))
  }
}
