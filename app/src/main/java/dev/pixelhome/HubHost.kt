package dev.pixelhome

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*

/** Android owns HOME selection and authentication; this app never bypasses either. */
class HubHost(private val activity: ComponentActivity) {
    private val preferences = activity.getSharedPreferences("hub_display", 0)
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private val roles = activity.getSystemService(RoleManager::class.java)
    var locked by mutableStateOf(true); private set
    var isDefaultHome by mutableStateOf(false); private set
    var showWhenLocked by mutableStateOf(preferences.getBoolean("show_when_locked", false)); private set
    var notice by mutableStateOf<String?>(null); private set
    var autoReturnOnLock by mutableStateOf(preferences.getBoolean("dock_auto_return", false)); private set
    var overlayPermission by mutableStateOf(Settings.canDrawOverlays(activity)); private set
    private val notificationRequest = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) autoReturn(true) else notice = "Automatic return stays off until notifications are allowed."
    }
    fun overlaySettings() {
        if(isLockedNow()) return
        try { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
        catch(_: ActivityNotFoundException) { notice = "Open Android app settings and grant Display over other apps." }
    }
    fun autoReturn(enabled: Boolean) {
        if(isLockedNow()) return
        if(enabled && !Settings.canDrawOverlays(activity)) { notice = "Grant Display over other apps below, then enable automatic return."; return }
        if(enabled && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        preferences.edit().putBoolean("dock_auto_return", enabled).apply(); autoReturnOnLock = enabled
        if(enabled) {
            // The existing protected-view setting is explicit and independently visible in Settings.
            showOverLock(true)
            startLockWatcherIfConfigured()
        } else activity.stopService(Intent(activity, DockReturnService::class.java))
    }
    fun startLockWatcherIfConfigured() {
        autoReturnOnLock = preferences.getBoolean("dock_auto_return", false)
        overlayPermission = Settings.canDrawOverlays(activity)
        if(!autoReturnOnLock || !overlayPermission || DockReturnService.running) return
        if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { notice = "Allow notifications to run the visible dock monitor."; return }
        try { activity.startForegroundService(Intent(activity, DockReturnService::class.java)) }
        catch(_: RuntimeException) { notice = "Android could not start dock monitoring. Reopen Hearthline to retry; automatic return is not currently running." }
    }
    var rearmOnLock by mutableStateOf(preferences.getBoolean("dock_rearm", true)); private set
    var keepDockAwake by mutableStateOf(preferences.getBoolean("dock_keep_awake", false)); private set
    var showLockedMusic by mutableStateOf(preferences.getBoolean("dock_show_music", true)); private set
    var showLockedDashboard by mutableStateOf(preferences.getBoolean("dock_dashboard", true)); private set
    var wakeScreenOnLock by mutableStateOf(preferences.getBoolean("dock_wake_on_lock", true)); private set
    var dashboardPreview by mutableStateOf(false); private set
    var onDeviceLocked: () -> Unit = {}
    private var previousActualLock: Boolean? = null
    private val settle = android.os.Handler(android.os.Looper.getMainLooper())
    fun previewDashboard(value: Boolean) { if(!isLockedNow()) dashboardPreview = value }
    fun lockedLayout(dashboard: Boolean = showLockedDashboard, wake: Boolean = wakeScreenOnLock) {
        if(isLockedNow()) return
        showLockedDashboard = dashboard; wakeScreenOnLock = wake
        preferences.edit().putBoolean("dock_dashboard",dashboard).putBoolean("dock_wake_on_lock",wake).apply()
    }
    fun dockEligible() = dockPresentationEligible(dock.physicalObservation,dock.observation,DockTestRuntime.active(android.os.SystemClock.elapsedRealtime()))
    var dockTestActive by mutableStateOf(false); private set
    fun assumeDockForTesting() {
        if(isLockedNow()) return
        DockTestRuntime.start(android.os.SystemClock.elapsedRealtime())
        lockPolicy.resume(); saveSuppression(); applyWindowPolicy()
    }
    fun endDockTest() { DockTestRuntime.end(); applyWindowPolicy() }
    private val lockPolicy = DockLockPolicy(preferences.getBoolean("dock_suppressed", false))
    val dock = DockMonitor(activity, { refresh() }, {
        // A second Power press while already locked must not undo Dismiss or relaunch in a loop.
        if(previousActualLock != true) lockPolicy.screenOff(rearmOnLock)
        saveSuppression(); refresh()
        for(delay in listOf(50L,100L,250L,500L,1000L)) settle.postDelayed({ refresh() },delay)
    })
    var lockScreenEligible by mutableStateOf(false); private set
    private var appliedWindowPolicy: Boolean? = null
    fun applyWindowPolicy() {
        dockTestActive = DockTestRuntime.active(android.os.SystemClock.elapsedRealtime())
        val eligible = lockPolicy.eligible(showWhenLocked || dockTestActive, dock.physicalObservation, dock.observation, foregroundTest = dockTestActive)
        lockScreenEligible = eligible
        if (appliedWindowPolicy != eligible) { activity.setShowWhenLocked(eligible); appliedWindowPolicy = eligible }
    }
    private fun saveSuppression() {
        if(preferences.getBoolean("dock_suppressed", false) != lockPolicy.suppressed)
            preferences.edit().putBoolean("dock_suppressed", lockPolicy.suppressed).apply()
    }
    fun start() {
        // Migrate the old opt-out: normal protected dock presentation requires hardware evidence.
        preferences.edit().putBoolean("dock_only", true).apply()
        if(preferences.getString("dock_mode", "AUTO") == "FORCE_DOCKED") preferences.edit().putString("dock_mode", "AUTO").apply()
        dock.changeMode(runCatching { DockDetectionMode.valueOf(preferences.getString("dock_mode", "AUTO")!!) }.getOrDefault(DockDetectionMode.AUTO))
        dock.start()
    }
    fun close() { settle.removeCallbacksAndMessages(null); dock.close() }
    fun configureDock(rearm: Boolean = rearmOnLock, awake: Boolean = keepDockAwake, music: Boolean = showLockedMusic) {
        if(isLockedNow()) return
        rearmOnLock = rearm; keepDockAwake = awake; showLockedMusic = music
        preferences.edit().putBoolean("dock_only", true).putBoolean("dock_rearm", rearm).putBoolean("dock_keep_awake", awake).putBoolean("dock_show_music", music).apply()
        refresh()
    }
    fun detectionMode(value: DockDetectionMode) {
        if(isLockedNow() || value == DockDetectionMode.FORCE_DOCKED) return
        preferences.edit().putString("dock_mode", value.name).apply(); dock.changeMode(value)
    }
    fun resumeDockDisplay() { if(!isLockedNow()) { lockPolicy.resume(); saveSuppression(); applyWindowPolicy() } }
    private val roleRequest = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    fun isLockedNow() = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    fun refresh() {
        autoReturnOnLock = preferences.getBoolean("dock_auto_return", false)
        overlayPermission = Settings.canDrawOverlays(activity)
        val actual = isLockedNow()
        val entering = actual && previousActualLock != true
        val leaving = !actual && previousActualLock != false
        previousActualLock = actual; locked = actual
        if(entering) { dashboardPreview = false; onDeviceLocked() }
        if(leaving) activity.setTurnScreenOn(false)
        lockPolicy.observeLock(locked, rearmOnLock); saveSuppression(); applyWindowPolicy()
        if(entering && lockScreenEligible && wakeScreenOnLock) activity.setTurnScreenOn(true)
        isDefaultHome = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }
    fun prepareDockReturn() {
        refresh()
        if(isLockedNow() && lockScreenEligible && wakeScreenOnLock) activity.setTurnScreenOn(true)
    }
    fun conceal() { locked = true }
    fun restorePreferences(vm: HubViewModel) {
        vm.hub(preferences.getBoolean("hub_enabled", true))
        vm.dark = preferences.getBoolean("dark", false)
    }
    fun persistPreferences(vm: HubViewModel) {
        preferences.edit().putBoolean("hub_enabled", vm.hubEnabled).putBoolean("dark", vm.dark).apply()
    }
    fun showOverLock(value: Boolean) {
        if (isLockedNow()) return
        showWhenLocked = value
        preferences.edit().putBoolean("show_when_locked", value).apply()
        if(!value) {
            autoReturnOnLock = false; preferences.edit().putBoolean("dock_auto_return", false).apply()
            activity.stopService(Intent(activity, DockReturnService::class.java))
        }
        lockPolicy.resume(); saveSuppression()
        applyWindowPolicy()
    }
    fun makeHome() {
        if (isLockedNow()) return
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try { roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            catch (_: ActivityNotFoundException) { homeSettings() }
        } else homeSettings()
    }
    fun homeSettings() {
        if (isLockedNow()) return
        try { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: ActivityNotFoundException) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    fun unlock() {
        if (!isLockedNow()) { refresh(); return }
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { notice = null; refresh() }
            override fun onDismissCancelled() { notice = "Still locked. Your device controls remain protected."; refresh() }
            override fun onDismissError() { notice = "Use Android's lock screen to unlock, then return to Hearthline."; refresh() }
        })
    }
    fun systemLockScreen() {
        lockPolicy.dismiss(); saveSuppression()
        applyWindowPolicy()
        activity.moveTaskToBack(true)
    }
}
val LocalHubHost = staticCompositionLocalOf<HubHost> { error("HubHost is required") }
