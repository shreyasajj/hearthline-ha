package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class DockPresentationTest {
    private val undocked=DockObservation(DockConnection.UNDOCKED,"no_dock_signal")
    private val docked=DockObservation(DockConnection.DOCKED,"audio_dock")
    @Test fun explicitTestLeaseEnablesDockPresentationWithoutChangingPhysicalState() {
        assertTrue(dockPresentationEligible(undocked,undocked,true))
        assertFalse(undocked.isConfirmedDock())
        assertFalse(dockPresentationEligible(undocked,undocked,false))
    }
    @Test fun ordinaryChargingAndOldManualOverridesNeverAuthorizeReturn() {
        assertFalse(dockPresentationEligible(DockObservation(DockConnection.UNKNOWN,"unconfirmed"),undocked))
        assertFalse(dockPresentationEligible(undocked,DockObservation(DockConnection.DOCKED,"manual_override",true)))
    }
    @Test fun endingOrExpiringTestRestoresPhysicalRequirement() {
        val lease=DockTestSession();lease.start(10)
        assertTrue(dockPresentationEligible(undocked,undocked,lease.active(11)))
        lease.end();assertFalse(dockPresentationEligible(undocked,undocked,lease.active(12)))
        lease.start(20);assertFalse(dockPresentationEligible(undocked,undocked,lease.active(20+DockTestSession.DURATION_MS)))
    }
    @Test fun simulatedDockStillCannotReturnWhileUnlockedOrDismissed() {
        val eligible=dockPresentationEligible(undocked,undocked,true)
        assertFalse(DockReturnGate().canReturn(true,true,false,eligible,false,true))
        assertFalse(DockReturnGate().canReturn(true,true,true,eligible,true,true))
    }
    @Test fun normalDockAndManualSuppressionArePreserved() {
        assertTrue(dockPresentationEligible(docked,docked))
        assertFalse(dockPresentationEligible(docked,undocked))
    }
}
