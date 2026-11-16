package dev.pixelhome

/** Explicit bounded local simulation; never persisted or used as physical docking evidence. */
class DockTestSession {
    companion object { const val DURATION_MS = 10 * 60 * 1000L }
    private var startedAt: Long? = null
    fun start(now: Long) { startedAt = now }
    fun end() { startedAt = null }
    fun active(now: Long): Boolean {
        val start = startedAt ?: return false
        if(now < start || now - start >= DURATION_MS) { end(); return false }
        return true
    }
}

/** Process-only lease shared by the activity and its explicitly enabled lock watcher. */
object DockTestRuntime {
    private val session = DockTestSession()
    @Synchronized fun start(now: Long) = session.start(now)
    @Synchronized fun end() = session.end()
    @Synchronized fun active(now: Long) = session.active(now)
}
