package br.com.leitorlocal.tradutor_webtoon

import org.junit.Assert.*
import org.junit.Test

class ScrollDebouncerTest {
    @Test fun waitsUntil550MillisecondsAndRestartsOnMotion() {
        val debounce = ScrollDebouncer()
        val first = debounce.motion(1000)
        assertFalse(debounce.stable(1549, first))
        val second = debounce.motion(1400)
        assertFalse(debounce.stable(1950, first))
        assertFalse(debounce.stable(1949, second))
        assertTrue(debounce.stable(1950, second))
    }
    @Test fun invalidationRejectsPreviouslyScheduledCallbacks() {
        val debounce = ScrollDebouncer()
        val capturedGeneration = debounce.motion(0)
        debounce.invalidate()
        assertFalse(debounce.stable(5000, capturedGeneration))
        assertFalse(debounce.stable(5000, debounce.generation))
    }
}
