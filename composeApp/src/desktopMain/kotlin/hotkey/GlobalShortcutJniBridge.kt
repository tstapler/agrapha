package com.meetingnotes.hotkey

import com.meetingnotes.audio.PipeWireCaptureJniBridge

/**
 * JNI bridge to the global hotkey functions in libagrapha_native.so.
 *
 * Shares the same native library as [PipeWireCaptureJniBridge] — load that first
 * (via [PipeWireCaptureJniBridge.tryLoad]) before calling any method here.
 *
 * All `native*` functions run on the calling thread. Call from [kotlinx.coroutines.Dispatchers.IO]
 * since [nativeRegisterAndWait] blocks until the hotkey fires or times out.
 */
internal object GlobalShortcutJniBridge {

    /**
     * Returns true if at least one hotkey backend is available on this system:
     *  - Wayland xdg-desktop-portal GlobalShortcuts (GNOME 46+, KDE Plasma 6)
     *  - X11 XGrabKey (pure X11 or XWayland Wayland session)
     */
    external fun nativeIsSupported(): Boolean

    /**
     * Register the hotkey and block until it fires, [timeoutMs] elapses, or [nativeInterrupt] is called.
     *
     * @param keyCode  X11 keycode (e.g. 65 = Space). Ignored on Wayland portal path.
     * @param modifiers X11 ModMask bitmask (e.g. 0x40 = Mod4/Super). Ignored on Wayland portal path.
     * @param timeoutMs maximum wait in ms; 0 = indefinite.
     * @return true if the hotkey fired; false on timeout or interrupt.
     */
    external fun nativeRegisterAndWait(keyCode: Int, modifiers: Int, timeoutMs: Long): Boolean

    /**
     * Unblock a blocked [nativeRegisterAndWait] call from any thread.
     * Safe to call even if no wait is in progress.
     */
    external fun nativeInterrupt()

    /**
     * Returns a human-readable description of the active backend, or an error message if unavailable.
     * Examples:
     *  - "Wayland xdg-desktop-portal GlobalShortcuts"
     *  - "X11 XGrabKey via XWayland"
     *  - "Unavailable: no DISPLAY or WAYLAND_DISPLAY"
     */
    external fun nativeBackendDescription(): String

    /** X11 keycode for the Space key (layout-dependent; correct for most en-US systems). */
    const val KEY_SPACE: Int = 65

    /** X11 ModMask for Mod4 (Super / Windows key). */
    const val MOD_SUPER: Int = 0x40

    /** Default hotkey: Super+Space. */
    const val DEFAULT_KEY_CODE: Int = KEY_SPACE
    const val DEFAULT_MODIFIERS: Int = MOD_SUPER
}
