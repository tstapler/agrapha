package com.meetingnotes.data.audio

import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.platform.Platform
import com.meetingnotes.transcription.PyannoteDiarizationBackend
import org.junit.Test
import kotlin.test.assertIs

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
    fun `fluida on macOS 14 falls back to PyannoteDiarizationBackend when dylib absent`() {
        // FluidAudioDiarizationBackend constructor will throw BackendUnavailableException
        // because the dylib is not present in the test classpath.
        val backend = AudioAiBackendFactory.createDiarizationBackend(
            settings("fluida"), macOs14
        )
        assertIs<PyannoteDiarizationBackend>(backend)
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
