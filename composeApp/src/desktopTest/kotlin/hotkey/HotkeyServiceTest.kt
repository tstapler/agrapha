package com.meetingnotes.hotkey

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class HotkeyServiceTest {

    // ── Mock bridge ───────────────────────────────────────────────────────────

    private class MockBridge(
        private val supported: Boolean = true,
        private val waitResult: Boolean = false,
        private val description: String = "MockBridge",
        val waitCalls: MutableList<Triple<Int, Int, Long>> = mutableListOf(),
        var interrupted: Boolean = false,
    ) : HotkeyService.HotkeyBridge {
        override fun isSupported() = supported
        override fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long): Boolean {
            waitCalls += Triple(keyCode, modifiers, timeoutMs)
            return waitResult
        }
        override fun interrupt() { interrupted = true }
        override fun backendDescription() = description
    }

    @Test
    fun `isAvailable delegates to bridge`() {
        assertTrue(HotkeyService(bridge = MockBridge(supported = true)).isAvailable)
        assertFalse(HotkeyService(bridge = MockBridge(supported = false)).isAvailable)
    }

    @Test
    fun `backendDescription delegates to bridge`() {
        val svc = HotkeyService(bridge = MockBridge(description = "X11 XGrabKey"))
        assertEquals("X11 XGrabKey", svc.backendDescription)
    }

    @Test
    fun `stop calls interrupt on bridge`() {
        val bridge = MockBridge()
        HotkeyService(bridge = bridge).stop()
        assertTrue(bridge.interrupted)
    }

    @Test
    fun `listen calls onHotkey when waitOnce returns true`() = runBlocking {
        val bridge = MockBridge(waitResult = true)
        val svc = HotkeyService(bridge = bridge)
        var hotkeyCount = 0

        val job = launch(Dispatchers.IO) {
            svc.listen { hotkeyCount++ }
        }

        delay(150) // real time — let the IO thread execute at least one iteration
        job.cancel()

        assertTrue(hotkeyCount > 0, "onHotkey should have been called at least once")
    }

    @Test
    fun `listen does not call onHotkey on timeout`() = runTest {
        val bridge = MockBridge(waitResult = false)
        val svc = HotkeyService(bridge = bridge)
        var hotkeyCount = 0

        val job = launch(Dispatchers.IO) {
            svc.listen { hotkeyCount++ }
        }
        delay(150)
        job.cancel()

        assertEquals(0, hotkeyCount, "onHotkey should not be called on timeout")
    }

    @Test
    fun `listen is a no-op when not supported`() = runTest {
        val bridge = MockBridge(supported = false, waitResult = true)
        val svc = HotkeyService(bridge = bridge)
        var hotkeyCount = 0

        val job = launch { svc.listen { hotkeyCount++ } }
        delay(50)
        job.cancel()

        assertEquals(0, hotkeyCount)
        assertTrue(bridge.waitCalls.isEmpty(), "waitOnce should never be called when not supported")
    }

    @Test
    fun `listen uses 1s timeout windows`() = runBlocking {
        val bridge = MockBridge(waitResult = false)
        val svc = HotkeyService(bridge = bridge)

        val job = launch(Dispatchers.IO) {
            svc.listen {}
        }
        delay(250) // real time — let several iterations complete
        job.cancelAndJoin() // wait for IO thread to finish current waitOnce() before reading waitCalls

        assertTrue(bridge.waitCalls.isNotEmpty())
        bridge.waitCalls.forEach { (_, _, timeoutMs) ->
            assertEquals(1_000L, timeoutMs)
        }
    }

    @Test
    fun `default key code and modifiers are Super+Space`() {
        assertEquals(GlobalShortcutJniBridge.KEY_SPACE,  GlobalShortcutJniBridge.DEFAULT_KEY_CODE)
        assertEquals(GlobalShortcutJniBridge.MOD_SUPER, GlobalShortcutJniBridge.DEFAULT_MODIFIERS)
    }
}
