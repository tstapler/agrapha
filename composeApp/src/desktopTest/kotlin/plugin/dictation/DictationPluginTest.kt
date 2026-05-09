package com.meetingnotes.plugin.dictation

import com.meetingnotes.dictation.TextInjector
import com.meetingnotes.dictation.plugin.DictationPlugin
import com.meetingnotes.plugin.DictationMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UNIT-5-1-01 through UNIT-5-1-05
 */
class DictationPluginTest {

    // ── UNIT-5-1-01 ──────────────────────────────────────────────────────────
    @Test
    fun `id equals com_agrapha_dictation`() {
        assertEquals("com.agrapha.dictation", DictationPlugin().id)
    }

    // ── UNIT-5-1-02 ──────────────────────────────────────────────────────────
    @Test
    fun `name equals Dictation`() {
        assertEquals("Dictation", DictationPlugin().name)
    }

    // ── UNIT-5-1-03 ──────────────────────────────────────────────────────────
    @Test
    fun `supportedModes contains all three DictationMode values`() {
        val modes = DictationPlugin().supportedModes
        assertEquals(
            setOf(DictationMode.PUSH_TO_TALK, DictationMode.FILE_TRANSCRIPTION, DictationMode.LIVE_CAPTIONS),
            modes
        )
    }

    // ── UNIT-5-1-04 ──────────────────────────────────────────────────────────
    @Test
    fun `FILE_TRANSCRIPTION activate with missing inputPath returns failure without throw`() {
        val plugin = DictationPlugin(whisperService = null)
        val result = runBlocking { plugin.activate(DictationMode.FILE_TRANSCRIPTION, emptyMap()) }
        assertTrue(result.isFailure, "activate must return failure when WhisperService is null or inputPath missing")
    }

    // ── UNIT-5-1-05 ──────────────────────────────────────────────────────────
    @Test
    fun `deactivate is idempotent — calling twice does not throw`() {
        val plugin = DictationPlugin()
        runBlocking {
            plugin.deactivate()
            plugin.deactivate()
        }
        // Must not throw
    }

    @Test
    fun `version is non-empty`() {
        assertTrue(DictationPlugin().version.isNotBlank())
    }

    @Test
    fun `PUSH_TO_TALK activate returns success (in-window MVP mode)`() {
        val plugin = DictationPlugin()
        val result = runBlocking { plugin.activate(DictationMode.PUSH_TO_TALK, emptyMap()) }
        assertTrue(result.isSuccess, "PUSH_TO_TALK activate must succeed in in-window MVP mode")
    }
}
