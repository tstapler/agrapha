package com.meetingnotes.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformInfoTest {

    // ── UNIT-1-1-01 ──────────────────────────────────────────────────────────
    @Test
    fun `isLinux returns true when os_name is linux lowercase`() {
        assertTrue(Platform(osName = "linux").isLinux())
    }

    // ── UNIT-1-1-02 ──────────────────────────────────────────────────────────
    @Test
    fun `isLinux returns true for Linux mixed case`() {
        assertTrue(Platform(osName = "Linux").isLinux())
    }

    // ── UNIT-1-1-03 ──────────────────────────────────────────────────────────
    @Test
    fun `isMac returns true when os_name starts with Mac`() {
        assertTrue(Platform(osName = "Mac OS X").isMac())
    }

    // ── UNIT-1-1-04 ──────────────────────────────────────────────────────────
    @Test
    fun `isLinux and isMac are mutually exclusive`() {
        val linux = Platform(osName = "Linux")
        val mac   = Platform(osName = "Mac OS X")
        assertFalse(linux.isMac())
        assertFalse(mac.isLinux())
    }

    // ── UNIT-1-1-05 ──────────────────────────────────────────────────────────
    @Test
    fun `isWayland returns true when WAYLAND_DISPLAY is set`() {
        val p = Platform(osName = "Linux", envProvider = { key ->
            if (key == "WAYLAND_DISPLAY") "wayland-0" else null
        })
        assertTrue(p.isWayland())
    }

    // ── UNIT-1-1-06 ──────────────────────────────────────────────────────────
    @Test
    fun `isWayland returns false when WAYLAND_DISPLAY is absent`() {
        val p = Platform(osName = "Linux", envProvider = { null })
        assertFalse(p.isWayland())
    }

    // ── UNIT-1-1-07 ──────────────────────────────────────────────────────────
    @Test
    fun `isX11 returns true when DISPLAY is set and WAYLAND_DISPLAY is absent`() {
        val p = Platform(osName = "Linux", envProvider = { key ->
            if (key == "DISPLAY") ":0" else null
        })
        assertTrue(p.isX11())
    }

    // ── UNIT-1-1-08 ──────────────────────────────────────────────────────────
    @Test
    fun `unknown OS returns false for both isLinux and isMac`() {
        val p = Platform(osName = "Windows 11")
        assertFalse(p.isLinux())
        assertFalse(p.isMac())
    }

    @Test
    fun `avx2Supported returns true on non-Linux platforms`() {
        val p = Platform(osName = "Mac OS X")
        assertTrue(p.avx2Supported())
    }

    @Test
    fun `isPipeWireAvailable returns false when XDG_RUNTIME_DIR is absent`() {
        val p = Platform(osName = "Linux", envProvider = { null })
        assertFalse(p.isPipeWireAvailable())
    }
}
