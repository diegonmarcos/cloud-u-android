package com.diegonmarcos.superapp.edgetest

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicInteger

/** Full-screen "app below the edge overlay": counts clicks and records whether it was ever backgrounded. */
class VictimActivity : Activity() {
    val clicks = AtomicInteger()
    val downs = AtomicInteger()
    @Volatile var stopped = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = View(this)
        v.layoutParams = ViewGroup.LayoutParams(-1, -1)
        v.setBackgroundColor(0xFF202830.toInt())
        v.setOnClickListener { clicks.incrementAndGet() }
        v.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) downs.incrementAndGet(); false }
        setContentView(v)
        instance = this
    }

    override fun onStop() { stopped = true; super.onStop() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object { @Volatile var instance: VictimActivity? = null }
}
