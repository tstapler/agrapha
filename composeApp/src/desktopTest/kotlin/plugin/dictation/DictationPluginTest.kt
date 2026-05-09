package com.meetingnotes.plugin.dictation

import com.meetingnotes.dictation.TextInjector
import com.meetingnotes.dictation.plugin.DictationPlugin
import com.meetingnotes.hotkey.HotkeyService
import com.meetingnotes.plugin.DictationMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `PUSH_TO_TALK activate returns success regardless of hotkey availability`() {
        // With hotkey unavailable — falls back to UI trigger only
        val unavailableBridge = object : HotkeyService.HotkeyBridge {
            override fun isSupported() = false
            override fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long) = false
            override fun interrupt() {}
            override fun backendDescription() = "test-unavailable"
        }
        val plugin = DictationPlugin(hotkeyService = HotkeyService(bridge = unavailableBridge))
        val result = runBlocking { plugin.activate(DictationMode.PUSH_TO_TALK, emptyMap()) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `PUSH_TO_TALK with available hotkey starts listener coroutine`() = runBlocking {
        var waitCalled = false
        val bridge = object : HotkeyService.HotkeyBridge {
            override fun isSupported() = true
            override fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long): Boolean {
                waitCalled = true
                return false // always timeout so no dictation is triggered
            }
            override fun interrupt() {}
            override fun backendDescription() = "test-x11"
        }
        val plugin = DictationPlugin(hotkeyService = HotkeyService(bridge = bridge))
        plugin.activate(DictationMode.PUSH_TO_TALK, emptyMap())
        delay(150) // allow listener loop to call waitOnce at least once
        plugin.deactivate()
        assertTrue(waitCalled, "HotkeyService listener should have called waitOnce")
    }

    @Test
    fun `deactivate calls hotkey stop`() = runBlocking {
        var stopped = false
        val bridge = object : HotkeyService.HotkeyBridge {
            override fun isSupported() = true
            override fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long) = false
            override fun interrupt() { stopped = true }
            override fun backendDescription() = "test"
        }
        val plugin = DictationPlugin(hotkeyService = HotkeyService(bridge = bridge))
        plugin.deactivate()
        assertTrue(stopped)
    }
}
