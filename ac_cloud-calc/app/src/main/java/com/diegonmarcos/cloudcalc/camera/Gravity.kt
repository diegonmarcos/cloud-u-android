package com.diegonmarcos.cloudcalc.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.diegonmarcos.cloudcalc.measure.Level
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The inclinometer's input (#772): the gravity sensor, else the raw accelerometer, in the phone's
 * own axes. [Level] turns a reading into angles; the user's zero lives in SharedPreferences
 * `cloud_camera`. No permission, no camera, no network.
 */
object Gravity {
    private const val PREFS = "cloud_camera"

    fun sensor(ctx: Context): Sensor? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    /** Readings until [stop] is called: (gx, gy, gz) at roughly [periodMs]. */
    fun listen(ctx: Context, periodMs: Int, onReading: (Double, Double, Double) -> Unit): () -> Unit {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sensor(ctx) ?: return {}
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { onReading(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()) }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, periodMs * 1000)
        return { sm.unregisterListener(l) }
    }

    /** One reading within [timeoutMs], for the debug route; null when no sensor answers. */
    fun once(ctx: Context, timeoutMs: Long): Triple<Double, Double, Double>? {
        val latch = CountDownLatch(1)
        var got: Triple<Double, Double, Double>? = null
        val stop = listen(ctx, 20) { x, y, z -> if (got == null) { got = Triple(x, y, z); latch.countDown() } }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { stop() }
        return got
    }

    fun zero(ctx: Context): Level.Tilt {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun d(k: String) = p.getString(k, null)?.toDoubleOrNull() ?: 0.0
        return Level.Tilt(d("zero_tilt"), d("zero_pitch"), d("zero_roll"), d("zero_edge"))
    }

    fun setZero(ctx: Context, t: Level.Tilt) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("zero_tilt", t.tiltDeg.toString()).putString("zero_pitch", t.pitchDeg.toString())
            .putString("zero_roll", t.rollDeg.toString()).putString("zero_edge", t.edgeDeg.toString()).commit()
    }
}
