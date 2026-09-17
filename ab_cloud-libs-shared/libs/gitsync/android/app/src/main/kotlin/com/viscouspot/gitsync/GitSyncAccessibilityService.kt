package com.viscouspot.gitsync

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import id.flutter.flutter_background_service.BackgroundService

class GitSyncAccessibilityService: AccessibilityService() {
    private lateinit var enabledInputMethods: List<String>

    override fun onCreate() {
        super.onCreate()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        enabledInputMethods = imm.enabledInputMethodList.map { it.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val accessibilityEventIntent = Intent(this, id.flutter.flutter_background_service.BackgroundService::class.java)
                    accessibilityEventIntent.action = "ACCESSIBILITY_EVENT"
                    accessibilityEventIntent.putExtra("packageName", (event.packageName?.toString() ?: ""))
                    accessibilityEventIntent.putExtra("enabledInputMethods", enabledInputMethods.joinToString(","))
                    startService(accessibilityEventIntent)
                }
                else -> {}
            }
        }
    }

    override fun onInterrupt() { }
}