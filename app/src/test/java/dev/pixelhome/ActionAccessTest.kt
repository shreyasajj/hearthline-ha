package dev.pixelhome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActionAccessTest {
    @Test fun noPlatformCheckMeansNoCommand() = runBlocking {
        val gate = ActionAccess()
        var sent = false
        assertFalse(gate.dispatch { sent = true })
        assertFalse(sent)
    }
    @Test fun commandQueuedWhileUnlockedIsRejectedAfterLock() = runBlocking {
        val gate = ActionAccess()
        var locked = false
        gate.install { !locked }
        val queueReleased = CompletableDeferred<Unit>()
        var sent = 0
        val pending = async { queueReleased.await(); gate.dispatch { sent++ } }
        locked = true
        queueReleased.complete(Unit)
        assertFalse(pending.await())
        assertEquals(0, sent)
        locked = false
        // Unlocking does not replay rejected actions; a fresh action is required.
        assertEquals(0, sent)
        assertTrue(gate.dispatch { sent++ })
        assertEquals(1, sent)
    }
    @Test fun lockingCannotRecallAnAlreadyDispatchedCommand() = runBlocking {
        val gate = ActionAccess()
        var locked = false
        gate.install { !locked }
        var completed = false
        assertTrue(gate.dispatch { locked = true; completed = true })
        assertTrue(completed)
        assertFalse(gate.dispatch { error("A second command must not run") })
    }
}
