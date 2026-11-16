package dev.pixelhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*

/** Process/activity-scoped public Android observation. No Google/vendor service or USB access. */
class DockMonitor(private val context: Context, private val changed: () -> Unit, private val screenOff: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var started = false
    private var signals = DockSignals()
    var mode by mutableStateOf(DockDetectionMode.AUTO); private set
    var physicalObservation by mutableStateOf(resolveDock(signals, DockDetectionMode.AUTO)); private set
    var observation by mutableStateOf(resolveDock(signals, mode)); private set
    var batteryPercent by mutableStateOf<Int?>(null); private set
    var powerSource by mutableStateOf("unknown"); private set
    var batteryStatus by mutableStateOf("unknown"); private set
    var charging by mutableStateOf<Boolean?>(null); private set
    val status: String get() = when {
        observation.overridden -> "${observation.connection.name.lowercase().replaceFirstChar { it.uppercase() }} · manual override"
        observation.connection == DockConnection.DOCKED -> "Dock connected · ${when(observation.evidence) { "audio_dock" -> "Android audio dock"; "dock_power" -> "Android dock power"; else -> "Android dock event" }}"
        observation.connection == DockConnection.UNDOCKED -> "Undocked · no dock signal"
        else -> "Dock unconfirmed · $powerSource power is not dock confirmation"
    }
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { readAudio(); publish() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { readAudio(); publish() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> readBattery(intent)
                Intent.ACTION_DOCK_EVENT -> signals = signals.copy(dockEvent = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1).takeIf { it in 0..4 })
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> readAudio()
            }
            publish()
        }
    }
    private fun readAudio() {
        signals = try {
            val dock = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_DOCK || Build.VERSION.SDK_INT >= 34 && it.type == AudioDeviceInfo.TYPE_DOCK_ANALOG
            }
            signals.copy(dockAudio = dock, audioObserved = true)
        } catch (_: Exception) { signals.copy(dockAudio = false, audioObserved = false) }
    }
    private fun readBattery(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryPercent = if (level >= 0 && scale > 0 && level <= scale) (level * 100 / scale).coerceIn(0, 100) else null
        batteryStatus = when(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            when(it) { BatteryManager.BATTERY_STATUS_CHARGING -> true
                BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null }
        }
        val dockPower = Build.VERSION.SDK_INT >= 33 && plugged >= 0 && plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0
        powerSource = when {
            plugged < 0 -> "unknown"
            plugged == 0 -> "none"
            Integer.bitCount(plugged) > 1 -> "multiple"
            dockPower -> "dock"
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
            else -> "unknown"
        }
        signals = signals.copy(dockPower = dockPower, externalPower = plugged > 0)
    }
    private fun publish() {
        physicalObservation = resolveDock(signals, DockDetectionMode.AUTO)
        observation = resolveDock(signals, mode); changed()
    }
    fun changeMode(value: DockDetectionMode) { mode = value; publish() }
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_DOCK_EVENT)
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::readBattery)
        context.registerReceiver(null, IntentFilter(Intent.ACTION_DOCK_EVENT))?.let {
            signals = signals.copy(dockEvent = it.getIntExtra(Intent.EXTRA_DOCK_STATE, -1).takeIf { state -> state in 0..4 })
        }
        audio.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        readAudio(); publish()
    }
    fun close() {
        if (!started) return
        started = false; audio.unregisterAudioDeviceCallback(audioCallback); context.unregisterReceiver(receiver)
    }
}
