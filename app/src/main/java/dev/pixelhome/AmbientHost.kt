package dev.pixelhome

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class AmbientHost(private val activity: Activity) : SensorEventListener {
    private val prefs = activity.getSharedPreferences("ambient_display",Context.MODE_PRIVATE)
    var settings by mutableStateOf(AmbientSettings(
        enabled=prefs.getBoolean("enabled",true), idleSeconds=prefs.getInt("idle",60).coerceIn(15,1800),
        dimPercent=prefs.getInt("dim",3).coerceIn(1,15), activePercent=prefs.getInt("active",0).let { if(it==0) 0 else it.coerceIn(5,100) },
        autoDark=prefs.getBoolean("auto_dark",true), blackInDark=prefs.getBoolean("black_dark",true),alwaysBlack=prefs.getBoolean("black",false),
        keepClockDocked=prefs.getBoolean("keep_docked",true),wakeOnMusic=prefs.getBoolean("music",true),
        proximityWake=prefs.getBoolean("proximity",false),faceWake=prefs.getBoolean("face",false),voiceWake=prefs.getBoolean("voice",false),presenceEntity=prefs.getString("presence","").orEmpty()
    )); private set
    private val policy = AmbientPolicy(now())
    private val room = RoomLight()
    private val sensors = activity.getSystemService(SensorManager::class.java)
    val lightAvailable = sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    val proximityAvailable = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    var lux by mutableStateOf<Float?>(null); private set
    var roomDark by mutableStateOf<Boolean?>(null); private set
    var asleep by mutableStateOf(false); private set
    var voiceStatus by mutableStateOf("Microphone off"); private set
    var cameraStatus by mutableStateOf("Front camera off"); private set
    var presenceStatus by mutableStateOf("HA presence not configured"); private set
    var notice by mutableStateOf<String?>(null); private set
    private var resumed = false
    private var near = false
    private var lastFace: Long? = null
    private val voice = OfflineWake(activity.applicationContext,{voiceStatus=it;if(it.contains("unavailable") || it.contains("permission needed")) notice=it},{wake()})
    private val faces = FrontPresence(activity.applicationContext,{
        when(activity.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    },{cameraStatus=it;if(it.contains("unavailable") || it.contains("permission needed")) notice=it},{lastFace=now();wake()})
    private var appliedBrightness: Float? = null
    private var immersive = false
    private var lastTouchAt = 0L
    private fun now() = SystemClock.elapsedRealtime()
    fun change(next: AmbientSettings) {
        val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
        if(keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return
        try { settings = next.validated() } catch(_: Exception) { notice = "Use a binary_sensor entity ID, or leave presence blank."; return }
        prefs.edit().putBoolean("enabled",next.enabled).putInt("idle",next.idleSeconds).putInt("dim",next.dimPercent).putInt("active",next.activePercent)
            .putBoolean("auto_dark",next.autoDark).putBoolean("black_dark",next.blackInDark).putBoolean("black",next.alwaysBlack).putBoolean("keep_docked",next.keepClockDocked)
            .putBoolean("music",next.wakeOnMusic).putBoolean("proximity",next.proximityWake).putBoolean("face",next.faceWake).putBoolean("voice",next.voiceWake).putString("presence",next.presenceEntity).apply()
        notice = null; wake()
        if(!next.faceWake || !next.enabled) faces.watching(false)
        if(resumed) registerSensors()
    }
    fun permissionNotice(kind: String) { notice = "$kind permission was not allowed. Touch and other configured wake signals still work." }
    fun wake() { policy.wake(now()); asleep = false; voice.listening(false); applyBrightness() }
    fun interaction() { val n = now(); if(n-lastTouchAt > 250) { lastTouchAt=n;wake() } }
    fun preview() { if(settings.enabled) { policy.preview(); asleep=true;applyBrightness() } }
    fun resume() { resumed=true;wake();registerSensors() }
    fun pause() {
        resumed=false;sensors.unregisterListener(this);near=false;lastFace=null
        voice.listening(false);faces.watching(false);asleep=false;policy.wake(now());restoreWindow()
    }
    private fun registerSensors() {
        sensors.unregisterListener(this)
        if(settings.autoDark || settings.enabled) sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL,1_000_000) }
        if(settings.enabled && settings.proximityWake) sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL) }
    }
    override fun onSensorChanged(event: SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_LIGHT -> { val value=event.values[0];if(value.isFinite() && value>=0) { if(lux==null || kotlin.math.abs(value-lux!!) >= maxOf(1f,lux!!*.2f)) lux=value;room.observe(value,now()) } }
            Sensor.TYPE_PROXIMITY -> { near=event.values[0] < minOf(event.sensor.maximumRange,5f);if(near && settings.proximityWake) wake() }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    fun tick(vm: HubViewModel, receiver: SendspinReceiver, host: HubHost) {
        if(!resumed) return
        val n=now();room.tick(n);roomDark=room.dark
        val allowed = !host.locked || host.lockScreenEligible
        val editing = vm.page == PlaybackNavigation.Page.SETTINGS && !host.locked
        val busy = vm.busy || vm.assistant.answer != null || vm.connectionDraft.scannerRequested || vm.connectionDraft.importingImage
        val haPresent = AmbientSignals.presence(vm.entities[settings.presenceEntity]?.state,vm.demo,vm.connected,vm.lastStateAt,n)
        presenceStatus = if(settings.presenceEntity.isEmpty()) "HA presence not configured" else when(haPresent) {true -> "HA presence: on";false -> "HA presence: off";null -> "HA presence: unavailable / stale"}
        val realMusic = receiver.audioPlaying || (!vm.demo && vm.connected && vm.playing && vm.lastStateAt?.let { n-it in 0..20000 } == true)
        asleep = policy.asleep(n,settings,allowed,busy,editing,realMusic,haPresent==true || settings.proximityWake&&near || settings.faceWake&&lastFace?.let { n-it in 0..3000 }==true)
        // No camera in credential/settings/answer flows; QR scanning owns the camera there.
        faces.watching(settings.enabled && settings.faceWake && allowed && !editing && !busy && !realMusic)
        voice.listening(settings.enabled && settings.voiceWake && asleep && allowed)
        applyBrightness()
    }
    fun dark(manual: Boolean) = settings.alwaysBlack || manual || settings.autoDark && roomDark==true
    fun black(manual: Boolean) = settings.alwaysBlack || settings.blackInDark && dark(manual)
    fun keepScreenOn(vm: HubViewModel,host: HubHost): Boolean {
        val docked=host.dock.physicalObservation.isConfirmedDock() || host.dockTestActive
        if(settings.enabled && settings.keepClockDocked && docked && (!host.locked || host.lockScreenEligible)) return true
        if(asleep) return false
        return vm.hubEnabled && !host.locked || host.locked && host.lockScreenEligible && host.keepDockAwake
    }
    private fun applyBrightness() {
        if(!resumed) return
        val brightness = if(asleep) (if(roomDark==true) minOf(settings.dimPercent,2) else settings.dimPercent)/100f else if(settings.activePercent==0) -1f else settings.activePercent/100f
        if(appliedBrightness != brightness) { activity.window.attributes=activity.window.attributes.apply { screenBrightness=brightness };appliedBrightness=brightness }
        if(immersive != asleep) {
            immersive=asleep
            val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(asleep) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun restoreWindow() {
        activity.window.attributes=activity.window.attributes.apply { screenBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE };appliedBrightness=null
        WindowCompat.getInsetsController(activity.window,activity.window.decorView).show(WindowInsetsCompat.Type.systemBars());immersive=false
    }
    fun close() { pause();voice.close();faces.close() }
}
val LocalAmbient = staticCompositionLocalOf<AmbientHost> { error("Ambient host required") }
