package com.diegonmarcos.superapp.edgetest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Full-screen "app below the edge overlay". It runs in the TEST package's own process (a foreign uid, like a
 * real app), so it reports through logcat tag [TAG]: "ready", "down", "click", "stop" - the test reads them back.
 */
class VictimActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = View(this)
        v.layoutParams = ViewGroup.LayoutParams(-1, -1)
        v.setBackgroundColor(0xFF202830.toInt())
        v.setOnClickListener { Log.i(TAG, "click") }
        v.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) Log.i(TAG, "down"); false }
        setContentView(v)
    }

    override fun onResume() { super.onResume(); Log.i(TAG, "ready") }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); Log.i(TAG, "ready") }
    override fun onStop() { Log.i(TAG, "stop"); super.onStop() }

    companion object { const val TAG = "EdgeVictim" }
}
