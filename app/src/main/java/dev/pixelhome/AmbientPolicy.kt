package dev.pixelhome

/** App presentation only: never changes Android lock state or global battery saver. */
data class AmbientSettings(
    val enabled: Boolean = true,
    val idleSeconds: Int = 60,
    val dimPercent: Int = 3,
    val activePercent: Int = 0, // 0 delegates to Android's brightness policy
    val autoDark: Boolean = true,
    val blackInDark: Boolean = true,
    val alwaysBlack: Boolean = false,
    val keepClockDocked: Boolean = true,
    val wakeOnMusic: Boolean = true,
    val proximityWake: Boolean = false,
    val faceWake: Boolean = false,
    val voiceWake: Boolean = false,
    val presenceEntity: String = "",
) {
    fun validated(): AmbientSettings {
        require(idleSeconds in 15..1800 && dimPercent in 1..15 && (activePercent == 0 || activePercent in 5..100))
        require(presenceEntity.isEmpty() || presenceEntity.matches(Regex("binary_sensor\\.[a-z0-9_]{1,100}")))
        return this
    }
}
class AmbientPolicy(startedAt: Long) {
    private var lastActivity = startedAt
    private var preview = false
    fun wake(now: Long) { lastActivity = now; preview = false }
    fun preview() { preview = true }
    fun asleep(now: Long, settings: AmbientSettings, eligible: Boolean, busy: Boolean, editing: Boolean, music: Boolean, present: Boolean): Boolean {
        if(now < lastActivity || !settings.enabled || !eligible || busy || music && settings.wakeOnMusic || present || editing && !preview) {
            wake(now); return false
        }
        return preview || now - lastActivity >= settings.idleSeconds * 1000L
    }
}
/** Hysteresis and dwell prevent a hand shadow from flickering the whole UI. */
class RoomLight {
    var dark: Boolean? = null; private set
    private var pending: Boolean? = null
    private var pendingAt = 0L
    fun observe(lux: Float, now: Long) {
        if(!lux.isFinite() || lux < 0) return
        val candidate = when { lux <= 8 -> true; lux >= 20 -> false; else -> dark }
        if(candidate == null || candidate == dark) { pending = null; return }
        if(pending != candidate) { pending = candidate; pendingAt = now }
    }
    fun tick(now: Long) {
        if(pending != null && now - pendingAt >= 3000) { dark = pending; pending = null }
    }
}
object AmbientSignals {
    fun presence(state: String?, demo: Boolean, connected: Boolean, refreshedAt: Long?, now: Long): Boolean? {
        if(demo || !connected || refreshedAt == null || now - refreshedAt !in 0..20000) return null
        return when(state) { "on" -> true; "off" -> false; else -> null }
    }
    fun wakePhrase(text: String): Boolean = text.length <= 64 && text.trim().lowercase().replace(Regex("\\s+")," ") == "wake up"
}
