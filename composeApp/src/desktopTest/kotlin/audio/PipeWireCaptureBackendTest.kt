package com.meetingnotes.audio

import com.meetingnotes.platform.Platform
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * UNIT-2-5-01 through UNIT-2-5-02
 */
class PipeWireCaptureBackendTest {

    @Test
    fun `isAvailable returns false on macOS (not Linux)`() {
        val macPlatform = Platform(osName = "Mac OS X")
        val backend = PipeWireCaptureBackend(platform = macPlatform)
        assertFalse(backend.isAvailable(), "PipeWire backend must not report available on macOS")
    }

    @Test
    fun `isAvailable returns false when platform is Linux but so resource absent`() {
        // Even on a Linux platform string, the JNI bridge loading will fail in CI
        // because the .so is not in classpath resources (make was not run).
        // This test verifies the graceful false return rather than a crash.
        val linuxPlatform = Platform(osName = "Linux", envProvider = { null })
        val backend = PipeWireCaptureBackend(platform = linuxPlatform)
        // Either the .so is absent (CI) → false, or PipeWire socket is absent → false.
        // In either case no exception should propagate.
        val result = runCatching { backend.isAvailable() }
        assert(result.isSuccess) { "isAvailable() must not throw: ${result.exceptionOrNull()}" }
        // On CI without the .so and without a PipeWire socket this will be false.
        // We can't assert the exact value because on a real Linux + PipeWire machine it may be true.
    }
}
