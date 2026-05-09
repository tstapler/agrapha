package com.meetingnotes.injection

import com.meetingnotes.dictation.AutoDetectTextInjector
import com.meetingnotes.dictation.TextInjector
import com.meetingnotes.dictation.TextInjectorUnavailableException
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Controllable stub for [TextInjector]. */
private class StubInjector(private val available: Boolean) : TextInjector {
    var injectCalled = false
    var isAvailableCallCount = 0

    override fun checkStatus(): TextInjector.Status =
        if (available) TextInjector.Status.OK else TextInjector.Status.NOT_INSTALLED

    override fun isAvailable(): Boolean {
        isAvailableCallCount++
        return available
    }

    override fun inject(text: String): Result<Unit> {
        injectCalled = true
        return Result.success(Unit)
    }
}

class AutoDetectTextInjectorTest {

    // ── UNIT-4-4-01 ──────────────────────────────────────────────────────────
    @Test
    fun `selects first available candidate (ydotool) when both available`() {
        val ydotool = StubInjector(available = true)
        val xdotool = StubInjector(available = true)
        val detector = AutoDetectTextInjector(listOf(ydotool, xdotool))

        assertTrue(detector.isAvailable())
        detector.inject("test")
        assertTrue(ydotool.injectCalled, "ydotool must be preferred when available")
        assertFalse(xdotool.injectCalled)
    }

    // ── UNIT-4-4-02 ──────────────────────────────────────────────────────────
    @Test
    fun `falls back to xdotool when ydotool unavailable`() {
        val ydotool = StubInjector(available = false)
        val xdotool = StubInjector(available = true)
        val detector = AutoDetectTextInjector(listOf(ydotool, xdotool))

        assertTrue(detector.isAvailable())
        detector.inject("test")
        assertFalse(ydotool.injectCalled)
        assertTrue(xdotool.injectCalled, "xdotool must be used when ydotool unavailable")
    }

    // ── UNIT-4-4-03 ──────────────────────────────────────────────────────────
    @Test
    fun `inject returns failure wrapping TextInjectorUnavailableException when no candidate available`() {
        val detector = AutoDetectTextInjector(listOf(
            StubInjector(available = false),
            StubInjector(available = false),
        ))

        assertFalse(detector.isAvailable())
        val result = detector.inject("hi")
        assertTrue(result.isFailure)
        assertIs<TextInjectorUnavailableException>(result.exceptionOrNull())
    }

    // ── UNIT-4-4-04 ──────────────────────────────────────────────────────────
    @Test
    fun `caches selection across multiple inject calls`() {
        val ydotool = StubInjector(available = true)
        val detector = AutoDetectTextInjector(listOf(ydotool))

        detector.inject("first")
        detector.inject("second")
        detector.inject("third")

        // isAvailable should be called at most once per candidate (one detection pass)
        assertTrue(ydotool.isAvailableCallCount <= 1,
            "isAvailable must not be called more than once per candidate: was ${ydotool.isAvailableCallCount}")
    }

    // ── UNIT-4-4-05 ──────────────────────────────────────────────────────────
    @Test
    fun `isAvailable returns false when candidate list is empty`() {
        val detector = AutoDetectTextInjector(emptyList())
        assertFalse(detector.isAvailable())
    }
}
