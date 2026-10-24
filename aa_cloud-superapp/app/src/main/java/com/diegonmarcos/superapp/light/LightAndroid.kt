package com.diegonmarcos.superapp.light

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Light tool (#1155) - the Android half: real torch, clock, preferences, foreground service, tile tap. */
class AndroidTorch(ctx: Context) : Torch {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val id: String? = runCatching {
        cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
    override val maxLevel: Int = runCatching {
        if (Build.VERSION.SDK_INT >= 33 && id != null)
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 else 1
    }.getOrDefault(1)

    fun watch(onUnavailable: () -> Unit) {
        runCatching {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) onUnavailable() }
            }, Handler(Looper.getMainLooper()))
        }
    }

    override fun set(on: Boolean, level: Int): TorchResult {
        val cam = id ?: return TorchResult.Unavailable("no rear flash")
        return try {
            if (on && maxLevel > 1 && Build.VERSION.SDK_INT >= 33) cm.turnOnTorchWithStrengthLevel(cam, level)
            else cm.setTorchMode(cam, on)
            TorchResult.Ok
        } catch (e: CameraAccessException) {
            if (e.reason == CameraAccessException.CAMERA_IN_USE || e.reason == CameraAccessException.MAX_CAMERAS_IN_USE) TorchResult.InUse
            else TorchResult.Unavailable("camera error ${e.reason}")
        } catch (e: RuntimeException) {
            TorchResult.Unavailable(e.javaClass.simpleName)
        }
    }
}

class MainClock : LightClock {
    private val h = Handler(Looper.getMainLooper())
    override fun now() = SystemClock.elapsedRealtime()
    override fun postDelayed(ms: Long, r: Runnable): Any { h.postDelayed(r, ms); return r }
    override fun cancel(token: Any) { h.removeCallbacks(token as Runnable) }
}

class PrefsKv(private val p: SharedPreferences) : LightKv {
    override fun int(key: String, def: Int) = p.getInt(key, def)
    override fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun float(key: String, def: Float) = p.getFloat(key, def)
    override fun str(key: String, def: String) = p.getString(key, def) ?: def
    override fun put(values: Map<String, Any>) {
        val e = p.edit()
        values.forEach { (k, v) -> when (v) {
            is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v); is Float -> e.putFloat(k, v); else -> e.putString(k, v.toString())
        } }
        e.apply()
    }
}

object Light {
    @Volatile private var engine: LightEngine? = null
    fun engine(ctx: Context): LightEngine = engine ?: synchronized(this) {
        engine ?: run {
            val app = ctx.applicationContext
            val torch = AndroidTorch(app)
            LightEngine(torch, MainClock(), PrefsKv(app.getSharedPreferences("light_prefs", Context.MODE_PRIVATE))).also { e ->
                torch.watch { e.onTorchUnavailable() }
                e.addListener { LightService.sync(app, e) }
                engine = e
            }
        }
    }

    /** The Cloud > Apps > Primary Tools "Light" tile (`action:light`): flip the default light now, then open the page. */
    fun tileTap(ctx: Context) {
        engine(ctx).quickTap()
        ctx.startActivity(Intent(ctx, LightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/** Foreground service with an Off action: only alive while the torch is kept lit past the page (timer or Keep on). */
class LightService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val e = Light.engine(this)
        if (i?.action == ACTION_OFF) { e.offAll(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Light", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(this, 1, Intent(this, LightService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, LightActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Light is on").setContentText("Tap to open, or switch it off")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Off", off).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_OFF = "com.diegonmarcos.superapp.light.OFF"
        private const val CHANNEL = "light"
        private const val NOTIF_ID = 1155
        /** Service up while a lit torch must outlive the page; down once nothing is lit. */
        fun needed(e: LightEngine) = e.state.rearOn && (e.state.timerRunning || e.settings.keepOn)
        @Volatile private var requested = false
        fun sync(ctx: Context, e: LightEngine) {
            if (needed(e)) {
                if (!requested) { requested = true; runCatching { ctx.startForegroundService(Intent(ctx, LightService::class.java)) } }
            } else if (requested) { requested = false; ctx.stopService(Intent(ctx, LightService::class.java)) }
        }
    }
}
