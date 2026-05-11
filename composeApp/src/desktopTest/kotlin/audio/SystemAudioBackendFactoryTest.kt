package com.meetingnotes.audio

import com.meetingnotes.platform.Platform
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * UNIT-2-6-01 through UNIT-2-6-03
 */
class SystemAudioBackendFactoryTest {

    @Test
    fun `create returns ScreenCaptureBackend on macOS`() {
        val mac = Platform(osName = "Mac OS X")
        val backend = SystemAudioBackendFactory.create(mac)
        assertIs<ScreenCaptureBackend>(backend)
    }

    @Test
    fun `create returns PipeWireCaptureBackend on Linux`() {
        val linux = Platform(osName = "Linux")
        val backend = SystemAudioBackendFactory.create(linux)
        assertIs<PipeWireCaptureBackend>(backend)
    }

    @Test
    fun `create returns NoOpSystemAudioBackend on unknown OS`() {
        val win = Platform(osName = "Windows 11")
        val backend = SystemAudioBackendFactory.create(win)
        assertIs<NoOpSystemAudioBackend>(backend)
    }
}
