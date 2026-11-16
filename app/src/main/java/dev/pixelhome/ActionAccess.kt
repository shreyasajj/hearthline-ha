package dev.pixelhome

/** Check at dispatch time, not when a gesture or queued request was created. */
class ActionAccess {
    private var check: () -> Boolean = { false }
    fun install(check: () -> Boolean) { this.check = check }
    val allowed get() = check()
    suspend fun dispatch(action: suspend () -> Unit): Boolean {
        if (!allowed) return false
        action()
        return true
    }
}
