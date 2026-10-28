package br.com.leitorlocal.tradutor_webtoon

import org.junit.Assert.*
import org.junit.Test

class ManualReadingGateTest {
    @Test fun startsPausedAndCannotCaptureUntilUserResumes() {
        val gate = ManualReadingGate()
        assertTrue(gate.paused)
        assertFalse(gate.schedule())
        assertFalse(gate.begin())
        assertFalse(gate.acceptsContentChange())
        gate.resume()
        assertTrue(gate.schedule())
        assertTrue(gate.begin())
    }
    @Test fun pageUpdatesDoNotCancelPendingCaptureOrOnlineTranslation() {
        val gate = ManualReadingGate().apply { resume() }
        assertTrue(gate.acceptsContentChange())
        gate.schedule()
        repeat(100) { assertFalse(gate.acceptsContentChange()) }
        assertTrue(gate.begin())
        repeat(100) { assertFalse(gate.acceptsContentChange()) }
        gate.complete()
        assertTrue(gate.acceptsContentChange())
    }
    @Test fun userCanPauseInFlightAndStartAnotherTranslation() {
        val gate = ManualReadingGate().apply { resume(); schedule(); begin() }
        gate.pause()
        gate.complete() // A late completion cannot resume the session.
        assertTrue(gate.paused)
        assertFalse(gate.acceptsContentChange())
        assertFalse(gate.begin())
        gate.resume()
        assertTrue(gate.schedule())
        assertTrue(gate.begin())
    }
    @Test fun actualScrollingCanReplaceAnInFlightRequest() {
        val gate = ManualReadingGate().apply { resume(); schedule(); begin() }
        gate.invalidate()
        assertTrue(gate.schedule())
        assertFalse(gate.acceptsContentChange())
        assertTrue(gate.begin())
    }
}
