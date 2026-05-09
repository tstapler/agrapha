package com.meetingnotes.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * UNIT-2-1-01 through UNIT-2-1-05
 */
class SilentAudioBackendTest {

    private val backend = NoOpSystemAudioBackend()

    @Test
    fun `isAvailable returns false`() {
        assertFalse(backend.isAvailable())
    }

    @Test
    fun `startCapture returns false`() {
        assertFalse(backend.startCapture(16_000))
    }

    @Test
    fun `readBuffer returns 0`() {
        assertEquals(0, backend.readBuffer(FloatArray(1024)))
    }

    @Test
    fun `stopCapture does not throw`() {
        backend.stopCapture()  // must not throw
    }
}
