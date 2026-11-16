package dev.pixelhome

/** Charging is not physical dock confirmation; overrides remain separately identified. */
enum class DockDetectionMode { AUTO, FORCE_DOCKED, FORCE_UNDOCKED }
enum class DockConnection { DOCKED, UNDOCKED, UNKNOWN }
data class DockSignals(
    val dockEvent: Int? = null,
    val dockAudio: Boolean = false,
    val dockPower: Boolean = false,
    val externalPower: Boolean = false,
    val audioObserved: Boolean = false
)
data class DockObservation(val connection: DockConnection, val evidence: String, val overridden: Boolean = false)

fun resolveDock(signals: DockSignals, mode: DockDetectionMode): DockObservation = when {
    mode == DockDetectionMode.FORCE_DOCKED -> DockObservation(DockConnection.DOCKED, "manual_override", true)
    mode == DockDetectionMode.FORCE_UNDOCKED -> DockObservation(DockConnection.UNDOCKED, "manual_override", true)
    signals.dockAudio -> DockObservation(DockConnection.DOCKED, "audio_dock")
    signals.dockEvent != null && signals.dockEvent in 1..4 -> DockObservation(DockConnection.DOCKED, "system_dock_event")
    signals.dockPower -> DockObservation(DockConnection.DOCKED, "dock_power")
    signals.dockEvent == 0 || (!signals.externalPower && signals.audioObserved) -> DockObservation(DockConnection.UNDOCKED, "no_dock_signal")
    else -> DockObservation(DockConnection.UNKNOWN, "unconfirmed")
}

fun DockObservation.isConfirmedDock() = connection == DockConnection.DOCKED && !overridden &&
    evidence in setOf("audio_dock", "system_dock_event", "dock_power")

/** Session suppression survives recreation; Android continues to own authentication. */
class DockLockPolicy(initiallySuppressed: Boolean = false) {
    var suppressed = initiallySuppressed; private set
    private var previousLocked: Boolean? = null
    fun observeLock(locked: Boolean, rearm: Boolean) {
        if (rearm && (!locked || previousLocked == false && locked)) suppressed = false
        previousLocked = locked
    }
    fun screenOff(rearm: Boolean) { if (rearm) suppressed = false }
    fun dismiss() { suppressed = true }
    fun resume() { suppressed = false }
    fun eligible(enabled: Boolean, physical: DockObservation, selected: DockObservation = physical, foregroundTest: Boolean = false) =
        enabled && !suppressed && dockPresentationEligible(physical, selected, foregroundTest)
}

/** Only the explicit local test lease can substitute for physical evidence. */
fun dockPresentationEligible(physical: DockObservation, selected: DockObservation, testLease: Boolean = false) =
    testLease || physical.isConfirmedDock() && selected.connection == DockConnection.DOCKED
