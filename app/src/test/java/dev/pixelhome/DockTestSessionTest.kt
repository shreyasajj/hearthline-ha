package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class DockTestSessionTest {
    @Test fun inactiveUntilExplicitlyStarted() {
        val session = DockTestSession(); assertFalse(session.active(1))
        session.start(100); assertTrue(session.active(101))
    }
    @Test fun expiresAfterTenMinutesWithoutReactivating() {
        val session = DockTestSession(); session.start(100)
        assertTrue(session.active(100 + DockTestSession.DURATION_MS - 1))
        assertFalse(session.active(100 + DockTestSession.DURATION_MS))
        assertFalse(session.active(101))
    }
    @Test fun endingRestoresNormalMode() {
        val session = DockTestSession(); session.start(100); session.end()
        assertFalse(session.active(101))
    }
    @Test fun clockRegressionCancelsSimulation() {
        val session = DockTestSession(); session.start(100)
        assertFalse(session.active(99)); assertFalse(session.active(101))
    }
}
