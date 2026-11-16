package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class DockReturnGateTest {
    @Test fun neverReturnsWhileUserIsUnlocked() {
        assertFalse(DockReturnGate().canReturn(true,true,false,true,false,true))
    }
    @Test fun dismissedCurrentLockDoesNotReopen() {
        assertFalse(DockReturnGate().canReturn(true,true,true,true,true,true))
    }
    @Test fun requiresBothConfigurationAndAndroidPermission() {
        val gate=DockReturnGate()
        assertFalse(gate.canReturn(false,true,true,true,false,true))
        assertFalse(gate.canReturn(true,false,true,true,false,true))
        assertFalse(gate.canReturn(true,true,true,true,false,false))
    }
    @Test fun undockedOrUnknownEvidenceDoesNotReturn() {
        assertFalse(DockReturnGate().canReturn(true,true,true,false,false,true))
    }
    @Test fun atMostOneAttemptPerLockCycle() {
        val gate=DockReturnGate(); assertTrue(gate.canReturn(true,true,true,true,false,true))
        gate.markAttempted(); assertFalse(gate.canReturn(true,true,true,true,false,true))
    }
    @Test fun nextLockCanReturnAfterNormalUnlockedUse() {
        val gate=DockReturnGate(); gate.markAttempted(); gate.newLockCycle()
        assertFalse(gate.canReturn(true,true,false,true,false,true))
        assertTrue(gate.canReturn(true,true,true,true,false,true))
    }
}
