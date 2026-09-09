package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class OverlaySessionTest {
    @Test fun freshProcessIsOffWithStartDescription() {
        val session = OverlaySessionController()
        assertEquals(OverlayPhase.OFF, session.state.value.phase)
        assertEquals("Start overlay", session.state.value.buttonText)
        assertEquals("Tap to enable the floating scanner.", session.state.value.description)
    }

    @Test fun permissionPendingBlocksDuplicateStartsAndOnlyServiceMarksOn() {
        val session = OverlaySessionController()
        val id = session.beginStart()!!
        assertEquals(OverlayPhase.STARTING, session.state.value.phase)
        assertEquals("Starting...", session.state.value.buttonText)
        assertNull(session.beginStart())
        assertFalse(session.running(id + 1))
        assertTrue(session.isStarting(id))
        assertFalse(session.running(id))
        assertTrue(session.dispatched(id))
        assertFalse(session.dispatched(id))
        assertEquals("Starting scanner...", session.state.value.description)
        assertTrue(session.running(id))
        assertEquals(OverlayPhase.ON, session.state.value.phase)
        assertEquals("Stop overlay", session.state.value.buttonText)
        assertEquals("Scanner active. Tap to stop.", session.state.value.description)
        assertNull(session.beginStart())
    }

    @Test fun deniedPermissionsOrStartupFailureReturnOff() {
        for (reason in listOf("Denied", "Service failed", "Startup timed out")) {
            val session = OverlaySessionController()
            val id = session.beginStart()!!
            session.stopped(id, reason)
            assertEquals(OverlayPhase.OFF, session.state.value.phase)
            assertEquals(reason, session.state.value.error)
            assertFalse(session.running(id))
            assertNotNull(session.beginStart())
            assertNull(session.state.value.error)
        }
    }

    @Test fun stopWaitsForActualCleanupAndBlocksDoubleTaps() {
        val session = OverlaySessionController()
        val id = session.beginStart()!!
        session.dispatched(id)
        session.running(id)
        assertEquals(id, session.beginStop())
        assertEquals(OverlayPhase.STOPPING, session.state.value.phase)
        assertEquals("Stopping...", session.state.value.buttonText)
        assertNull(session.beginStop())
        assertNull(session.beginStart())
        session.stopped(id)
        assertEquals(OverlayPhase.OFF, session.state.value.phase)
        assertNull(session.state.value.error)
    }

    @Test fun notificationAndProjectionStopDoNotNeedActivityRequest() {
        val session = OverlaySessionController()
        val id = session.beginStart()!!
        session.dispatched(id)
        session.running(id)
        session.stopped(id, "Capture ended")
        assertEquals(OverlayPhase.OFF, session.state.value.phase)
        assertEquals("Capture ended", session.state.value.error)
    }

    @Test fun oldCallbackCannotStopNewRequestAndPendingConsentCannotRestartCancelledRequest() {
        val session = OverlaySessionController()
        val old = session.beginStart()!!
        session.beginStop()
        assertFalse(session.running(old))
        session.stopped(old)
        val next = session.beginStart()!!
        assertTrue(next > old)
        session.stopped(old, "late callback")
        assertTrue(session.isStarting(next))
        assertNull(session.state.value.error)
        session.dispatched(next)
        assertTrue(session.running(next))
        session.stopped(old)
        assertEquals(OverlayPhase.ON, session.state.value.phase)
    }

    @Test fun errorIsNotOverwrittenByDuplicateDestroyCallback() {
        val session = OverlaySessionController()
        val id = session.beginStart()!!
        session.stopped(id, "Permission revoked")
        session.stopped(id)
        assertEquals("Permission revoked", session.state.value.error)
    }

    @Test fun permissionRequestCanBeRestoredOnlyInTheSameLiveProcess() {
        val session = OverlaySessionController()
        val savedId = session.beginStart()!!
        assertTrue(session.state.value.awaitingPermission)
        assertTrue(session.isStarting(savedId))
        assertFalse(OverlaySessionController().isStarting(savedId))
        assertTrue(session.dispatched(savedId))
        assertFalse(session.state.value.awaitingPermission)
        session.stopped(savedId)
        assertFalse(session.isStarting(savedId))
    }
}
