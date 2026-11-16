package dev.pixelhome

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*

/** User-opted-in, local lock watcher. It never opens an activity while Android is unlocked. */
class DockReturnService : Service() {
    companion object {
        private const val CHANNEL = "dock_lock_return"
        private const val NOTIFICATION = 41
        const val AUTO_RETURN = "dev.pixelhome.AUTO_DOCK_RETURN"
        const val STOP = "dev.pixelhome.PAUSE_DOCK_RETURN"
        var running by mutableStateOf(false); private set
    }
    private val preferences by lazy { getSharedPreferences("hub_display", 0) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val gate = DockReturnGate()
    private lateinit var dock: DockMonitor
    private val check = Runnable { evaluate() }
    private var observedLocked: Boolean? = null
    private val watch = object : Runnable {
        override fun run() { evaluate(); if(running) handler.postDelayed(this,250L) }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "dock_mode" && ::dock.isInitialized) dock.changeMode(mode())
        handler.post(check)
    }
    private fun mode() = runCatching {
        DockDetectionMode.valueOf(preferences.getString("dock_mode", "AUTO")!!).takeUnless { it == DockDetectionMode.FORCE_DOCKED } ?: DockDetectionMode.AUTO
    }.getOrDefault(DockDetectionMode.AUTO)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Dock automatic return", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, DockReturnService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(dev.pixelhome.R.drawable.ic_home)
            .setContentTitle("Dock view · return on lock")
            .setContentText("Only opens while locked and dock-eligible. Tap to open; pause to stop monitoring.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pause auto-return", pause).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        running = true
        preferences.registerOnSharedPreferenceChangeListener(listener)
        dock = DockMonitor(this, { handler.post(check) }, {
            handler.removeCallbacks(check)
            for (delay in listOf(0L, 50L, 100L, 250L, 1000L)) handler.postDelayed(check, delay)
        })
        dock.changeMode(mode()); dock.start(); handler.post(watch)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            preferences.edit().putBoolean("dock_auto_return", false).apply()
            stopSelf()
        } else evaluate()
        // Force-stop / process death never authorizes an unsolicited background restart.
        return START_NOT_STICKY
    }
    private fun evaluate() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        if (!preferences.getBoolean("dock_auto_return", false) || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (!::dock.isInitialized) return
        val locked = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        if (!locked) {
            observedLocked = false; gate.newLockCycle()
            if(preferences.getBoolean("dock_rearm",true) && preferences.getBoolean("dock_suppressed",false)) preferences.edit().putBoolean("dock_suppressed",false).apply()
            return
        }
        if(observedLocked == false && preferences.getBoolean("dock_rearm",true)) preferences.edit().putBoolean("dock_suppressed",false).apply()
        observedLocked = true
        val testing = DockTestRuntime.active(android.os.SystemClock.elapsedRealtime())
        val eligible = dockPresentationEligible(dock.physicalObservation,dock.observation,testing)
        if (!eligible) { gate.newLockCycle(); return }
        if (!gate.canReturn(true, preferences.getBoolean("show_when_locked", false) || testing, locked, eligible,
                preferences.getBoolean("dock_suppressed", false), Settings.canDrawOverlays(this))) return
        gate.markAttempted()
        // Recheck immediately before launching; a late dock callback must not steal an unlocked session.
        if (!(keyguard.isKeyguardLocked || keyguard.isDeviceLocked) || !dockPresentationEligible(dock.physicalObservation,dock.observation,DockTestRuntime.active(android.os.SystemClock.elapsedRealtime()))) return
        try {
            startActivity(Intent(this, MainActivity::class.java).putExtra(AUTO_RETURN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: RuntimeException) {
            // The notification's explicit Open action remains available if this OS blocks automatic BAL.
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        if (::dock.isInitialized) dock.close()
        running = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
