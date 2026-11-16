package dev.pixelhome

/** An unlocked user session must never be interrupted by automatic dock presentation. */
class DockReturnGate {
    private var attempted = false
    fun newLockCycle() { attempted = false }
    fun canReturn(enabled: Boolean, displayEnabled: Boolean, locked: Boolean, dockEligible: Boolean,
                  suppressed: Boolean, permission: Boolean): Boolean =
        enabled && displayEnabled && locked && dockEligible && !suppressed && permission && !attempted
    fun markAttempted() { attempted = true }
}
