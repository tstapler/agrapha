package com.meetingnotes.transcription

import org.junit.Ignore
import org.junit.Test

/**
 * S3-UNIT-06 and S3-UNIT-07 — TranscriptionUseCase progress and cancellation.
 *
 * These tests require a real Whisper GGML model file on disk because
 * [DesktopTranscriptionUseCase] constructs [WhisperService] internally and
 * calls [WhisperJNI.loadLibrary()] at runtime. Until [WhisperService] is made
 * injectable these tests cannot run in CI without the model present.
 */
class TranscriptionUseCaseProgressTest {

    @Ignore("Requires Whisper model file — run manually with a real model")
    @Test
    fun `transcribe emits progress from 0 to 100`() {
        // When WhisperService is injectable:
        // 1. Create a mock WhisperService that emits progress callbacks
        // 2. Construct DesktopTranscriptionUseCase with the mock
        // 3. Collect the flow and assert progress values ascend 0 → 100
    }

    @Ignore("Requires Whisper model file — run manually with a real model")
    @Test
    fun `transcription flow completes with non-empty segment list`() {
        // When WhisperService is injectable:
        // 1. Create a mock WhisperService that returns test segments
        // 2. Collect the flow to completion
        // 3. Assert final emit is TranscriptionResult with non-empty segments
    }
}
