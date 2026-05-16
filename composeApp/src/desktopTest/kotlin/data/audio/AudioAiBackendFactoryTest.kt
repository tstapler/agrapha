package com.meetingnotes.data.audio

import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.platform.Platform
import com.meetingnotes.transcription.PyannoteDiarizationBackend
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioAiBackendFactoryTest {

    private val macOs14 = Platform(osName = "Mac OS X", osVersion = "14.4.1")
    private val macOs13 = Platform(osName = "Mac OS X", osVersion = "13.6.0")
    private val linux   = Platform(osName = "Linux",    osVersion = "6.6.0")

    // ── "python" key ─────────────────────────────────────────────────────────

    @Test
    fun `python key returns PyannoteDiarizationBackend`() {
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("python"), linux
        )
        assertIs<PyannoteDiarizationBackend>(backend)
    }

    // ── "onnx" key ───────────────────────────────────────────────────────────

    @Test
    fun `onnx key returns OnnxDiarizationBackend`() {
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("onnx"), linux
        )
        assertIs<OnnxDiarizationBackend>(backend)
    }

    // ── "fluida" key — macOS version gate ─────────────────────────────────���──

    @Test
    fun `fluida on macOS 13 falls back to PyannoteDiarizationBackend`() {
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("fluida"), macOs13
        )
        assertIs<PyannoteDiarizationBackend>(backend)
    }

    @Test
    fun `fluida on Linux falls back to PyannoteDiarizationBackend`() {
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("fluida"), linux
        )
        assertIs<PyannoteDiarizationBackend>(backend)
    }

    @Test
    fun `fluida on macOS 14 returns FluidAudio backend or falls back gracefully`() {
        // The factory either returns FluidAudioDiarizationBackend (dylib present, built in CI)
        // or falls back to PyannoteDiarizationBackend (dylib absent in local dev without build step).
        // Both outcomes are correct; what must NOT happen is an uncaught exception.
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("fluida"), macOs14
        )
        assertTrue(
            backend is FluidAudioDiarizationBackend || backend is PyannoteDiarizationBackend,
            "Expected FluidAudioDiarizationBackend or PyannoteDiarizationBackend, got ${backend::class.simpleName}"
        )
    }

    // ── unknown key ──────────────────────────────────────────────────────────

    @Test
    fun `unknown key falls back to PyannoteDiarizationBackend`() {
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("banana"), linux
        )
        assertIs<PyannoteDiarizationBackend>(backend)
    }

    private fun settings(backend: String) = AppSettings(diarizationBackend = backend)
}
