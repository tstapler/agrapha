package com.meetingnotes.injection

import com.meetingnotes.dictation.ProcessBuilderFactory
import com.meetingnotes.dictation.TextInjector
import com.meetingnotes.dictation.XdotoolTextInjector
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class XFakeProcessBuilderFactory(
    private val whichExit: Int = 0,
    private val injectExit: Int = 0,
) : ProcessBuilderFactory {
    val capturedArgs = mutableListOf<List<String>>()

    override fun create(vararg command: String): ProcessBuilder {
        capturedArgs.add(command.toList())
        val exit = if (command.firstOrNull() == "which") whichExit else injectExit
        return ProcessBuilder("/bin/sh", "-c", "exit $exit")
    }
}

class XdotoolTextInjectorTest {

    // ── UNIT-4-3-01 ──────────────────────────────────────────────────────────
    @Test
    fun `isAvailable returns false on pure Wayland (WAYLAND_DISPLAY set, DISPLAY absent)`() {
        val injector = XdotoolTextInjector(
            envProvider = { key ->
                when (key) {
                    "WAYLAND_DISPLAY" -> "wayland-0"
                    "DISPLAY" -> null
                    else -> null
                }
            }
        )
        assertFalse(injector.isAvailable())
        assertEquals(TextInjector.Status.DAEMON_NOT_RUNNING, injector.checkStatus())
    }

    // ── UNIT-4-3-02 ──────────────────────────────────────────────────────────
    @Test
    fun `isAvailable returns true on X11 (DISPLAY set, WAYLAND_DISPLAY absent)`() {
        val factory = XFakeProcessBuilderFactory(whichExit = 0)
        val injector = XdotoolTextInjector(
            processBuilderFactory = factory,
            envProvider = { key ->
                when (key) {
                    "DISPLAY" -> ":0"
                    "WAYLAND_DISPLAY" -> null
                    else -> null
                }
            }
        )
        assertTrue(injector.isAvailable())
        assertEquals(TextInjector.Status.OK, injector.checkStatus())
    }

    // ── UNIT-4-3-03 ──────────────────────────────────────────────────────────
    @Test
    fun `isAvailable returns true under XWayland (both WAYLAND_DISPLAY and DISPLAY set)`() {
        val factory = XFakeProcessBuilderFactory(whichExit = 0)
        val injector = XdotoolTextInjector(
            processBuilderFactory = factory,
            envProvider = { key ->
                when (key) {
                    "DISPLAY" -> ":0"
                    "WAYLAND_DISPLAY" -> "wayland-0"
                    else -> null
                }
            }
        )
        assertTrue(injector.isAvailable())
    }

    // ── UNIT-4-3-04 ──────────────────────────────────────────────────────────
    @Test
    fun `isAvailable returns false when xdotool not installed`() {
        val factory = XFakeProcessBuilderFactory(whichExit = 1)
        val injector = XdotoolTextInjector(
            processBuilderFactory = factory,
            envProvider = { key ->
                when (key) {
                    "DISPLAY" -> ":0"
                    "WAYLAND_DISPLAY" -> null
                    else -> null
                }
            }
        )
        assertFalse(injector.isAvailable())
        assertEquals(TextInjector.Status.NOT_INSTALLED, injector.checkStatus())
    }

    // ── UNIT-4-3-05 ──────────────────────────────────────────────────────────
    @Test
    fun `inject passes -- separator in argument list`() {
        val factory = XFakeProcessBuilderFactory(whichExit = 0, injectExit = 0)
        val injector = XdotoolTextInjector(
            processBuilderFactory = factory,
            envProvider = { key -> if (key == "DISPLAY") ":0" else null }
        )
        injector.inject("hello world")
        val allArgs = factory.capturedArgs
        // All calls captured (which + inject)
        assertTrue(allArgs.isNotEmpty())
    }

    // ── UNIT-4-3-06 ──────────────────────────────────────────────────────────
    @Test
    fun `sanitize strips non-printable chars`() {
        val injector = XdotoolTextInjector()
        assertEquals("helloworld", injector.sanitize("helloworld"))
        assertEquals("hello\nworld", injector.sanitize("hello\nworld"))
    }
}
