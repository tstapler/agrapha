package com.meetingnotes.hotkey

import com.meetingnotes.audio.PipeWireCaptureJniBridge
import com.meetingnotes.platform.PlatformInfo
import kotlinx.coroutines.*

/**
 * Runs a coroutine loop that calls [GlobalShortcutJniBridge.nativeRegisterAndWait] in 1-second
 * polling windows so the loop can be cancelled promptly via coroutine scope.
 *
 * When the hotkey fires, [onHotkey] is called on the caller's dispatcher context.
 *
 * Usage:
 * ```
 * val svc = HotkeyService()
 * val job = scope.launch { svc.listen(onHotkey = { triggerDictation() }) }
 * // ...
 * svc.stop()      // interrupt any blocked native wait
 * job.cancel()
 * ```
 *
 * @param keyCode   X11 keycode; default Super+Space.
 * @param modifiers X11 ModMask; default Mod4 (Super).
 */
class HotkeyService(
    private val keyCode: Int = GlobalShortcutJniBridge.DEFAULT_KEY_CODE,
    private val modifiers: Int = GlobalShortcutJniBridge.DEFAULT_MODIFIERS,
    private val bridge: HotkeyBridge = DefaultHotkeyBridge,
) {
    /** Abstracted for testing without native library. */
    interface HotkeyBridge {
        fun isSupported(): Boolean
        fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long): Boolean
        fun interrupt()
        fun backendDescription(): String
    }

    object DefaultHotkeyBridge : HotkeyBridge {
        override fun isSupported(): Boolean {
            if (!PlatformInfo.isLinux()) return false
            if (!PipeWireCaptureJniBridge.tryLoad()) return false
            return try { GlobalShortcutJniBridge.nativeIsSupported() } catch (_: Throwable) { false }
        }
        override fun waitOnce(keyCode: Int, modifiers: Int, timeoutMs: Long): Boolean {
            return try { GlobalShortcutJniBridge.nativeRegisterAndWait(keyCode, modifiers, timeoutMs) } catch (_: Throwable) { false }
        }
        override fun interrupt() {
            try { GlobalShortcutJniBridge.nativeInterrupt() } catch (_: Throwable) {}
        }
        override fun backendDescription(): String {
            return try { GlobalShortcutJniBridge.nativeBackendDescription() } catch (_: Throwable) { "unknown" }
        }
    }

    /** True if the native backend loaded and is available on this system. */
    val isAvailable: Boolean get() = bridge.isSupported()

    /**
     * Human-readable description of the hotkey backend.
     * Examples: "X11 XGrabKey", "Wayland xdg-desktop-portal GlobalShortcuts".
     */
    val backendDescription: String get() = bridge.backendDescription()

    fun stop() = bridge.interrupt()

    /**
     * Suspend until cancelled. Calls [onHotkey] each time the hotkey fires.
     * Must be called from a coroutine; blocks [Dispatchers.IO] internally.
     */
    suspend fun listen(onHotkey: suspend () -> Unit) {
        if (!isAvailable) {
            System.err.println("[HotkeyService] not available on this system; listen() is a no-op")
            return
        }
        System.err.println("[HotkeyService] starting with backend: ${backendDescription}")
        // Each poll is 1 second so the coroutine scope can cancel promptly
        while (currentCoroutineContext().isActive) {
            val fired = withContext(Dispatchers.IO) {
                bridge.waitOnce(keyCode, modifiers, timeoutMs = 1_000L)
            }
            if (fired && currentCoroutineContext().isActive) {
                onHotkey()
            }
        }
    }
}
