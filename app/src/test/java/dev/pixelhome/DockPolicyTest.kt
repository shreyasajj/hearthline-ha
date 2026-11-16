package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class DockPolicyTest {
    private val docked = DockObservation(DockConnection.DOCKED, "audio_dock")
    private val undocked = DockObservation(DockConnection.UNDOCKED, "no_dock_signal")
    @Test fun chargingAloneDoesNotConfirmDock() {
        assertEquals(DockConnection.UNKNOWN, resolveDock(DockSignals(externalPower = true, audioObserved = true), DockDetectionMode.AUTO).connection)
    }
    @Test fun actualDockAudioConfirmsEvenWhenBatteryReportsAc() {
        val result = resolveDock(DockSignals(dockAudio = true, externalPower = true, audioObserved = true), DockDetectionMode.AUTO)
        assertEquals(docked, result)
    }
    @Test fun dockPowerAndSystemEventAreIndependentEvidence() {
        assertEquals("dock_power", resolveDock(DockSignals(dockPower = true), DockDetectionMode.AUTO).evidence)
        assertEquals("system_dock_event", resolveDock(DockSignals(dockEvent = 1), DockDetectionMode.AUTO).evidence)
    }
    @Test fun absentObservationsRemainUnknown() {
        assertEquals(DockConnection.UNKNOWN, resolveDock(DockSignals(), DockDetectionMode.AUTO).connection)
    }
    @Test fun audioRemovalStopsDockOnlyView() {
        val policy = DockLockPolicy()
        assertTrue(policy.eligible(true, docked))
        val noDock = resolveDock(DockSignals(externalPower = true, audioObserved = true), DockDetectionMode.AUTO)
        assertFalse(policy.eligible(true, noDock))
    }
    @Test fun manualOverridesAreExplicitAndTakePriority() {
        val manual = resolveDock(DockSignals(), DockDetectionMode.FORCE_DOCKED)
        assertTrue(manual.overridden); assertEquals("manual_override", manual.evidence)
        assertEquals(DockConnection.UNDOCKED, resolveDock(DockSignals(dockAudio = true), DockDetectionMode.FORCE_UNDOCKED).connection)
    }
    @Test fun disabledAndPhysicalDockRequirementGatePresentation() {
        val policy = DockLockPolicy()
        assertFalse(policy.eligible(false, docked))
        assertFalse(policy.eligible(true, undocked))
        assertTrue(policy.eligible(true, docked))
    }
    @Test fun manualDockAssumptionCannotSubstituteForPhysicalConnection() {
        val manual = resolveDock(DockSignals(), DockDetectionMode.FORCE_DOCKED)
        assertFalse(DockLockPolicy().eligible(true, undocked, manual))
        assertFalse(DockLockPolicy().eligible(true, manual))
    }
    @Test fun unknownPhysicalDockNeverShowsProtectedView() {
        val unknown = resolveDock(DockSignals(externalPower = true, audioObserved = true), DockDetectionMode.AUTO)
        assertFalse(DockLockPolicy().eligible(true, unknown))
    }
    @Test fun explicitForegroundTestCanPreviewWithoutPhysicalDock() {
        val policy = DockLockPolicy()
        assertTrue(policy.eligible(true, undocked, foregroundTest = true))
        assertFalse(policy.eligible(true, undocked))
        assertFalse(undocked.isConfirmedDock())
    }
    @Test fun foregroundTestStillRespectsDismissal() {
        val policy = DockLockPolicy(); policy.dismiss()
        assertFalse(policy.eligible(true, undocked, foregroundTest = true))
    }
    @Test fun dismissalIsRespectedDuringCurrentLock() {
        val policy = DockLockPolicy(); policy.observeLock(true, true); policy.dismiss(); policy.observeLock(true, true)
        assertFalse(policy.eligible(true, docked))
    }
    @Test fun unlockAndNextLockRearmWithoutBypassingAuthentication() {
        val policy = DockLockPolicy(); policy.observeLock(true, true); policy.dismiss()
        policy.observeLock(false, true); policy.observeLock(true, true)
        assertTrue(policy.eligible(true, docked))
    }
    @Test fun nextScreenOffRearmsWhenEnabled() {
        val policy = DockLockPolicy(); policy.dismiss(); policy.screenOff(true)
        assertFalse(policy.suppressed)
    }
    @Test fun rearmDisabledRequiresExplicitResume() {
        val policy = DockLockPolicy(); policy.dismiss(); policy.observeLock(false, false); policy.observeLock(true, false); policy.screenOff(false)
        assertTrue(policy.suppressed); policy.resume(); assertFalse(policy.suppressed)
    }
    @Test fun recreationKeepsDismissedLockSuppressed() {
        val policy = DockLockPolicy(initiallySuppressed = true); policy.observeLock(true, true)
        assertFalse(policy.eligible(true, docked))
    }
}
