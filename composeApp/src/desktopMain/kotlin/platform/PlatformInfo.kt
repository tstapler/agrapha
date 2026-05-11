package com.meetingnotes.platform

/**
 * Testable platform detection utility.
 *
 * The companion object [PlatformInfo] reads from [System.getProperty] and environment variables.
 * Tests can instantiate [Platform] directly, injecting fake values.
 */
open class Platform(
    private val osName: String = System.getProperty("os.name") ?: "",
    private val envProvider: (String) -> String? = { System.getenv(it) },
    private val osVersion: String = System.getProperty("os.version") ?: "",
) {
    fun isLinux(): Boolean = osName.lowercase().contains("linux")
    fun isMac(): Boolean = osName.lowercase().startsWith("mac")
    fun isWindows(): Boolean = osName.lowercase().contains("windows")

    /** Returns the macOS major version (e.g. 14 for macOS 14 Sonoma), or 0 on non-macOS. */
    fun macOsMajorVersion(): Int {
        if (!isMac()) return 0
        return osVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }

    fun isWayland(): Boolean = envProvider("WAYLAND_DISPLAY") != null
    fun isX11(): Boolean = envProvider("DISPLAY") != null && !isWayland()

    fun isPipeWireAvailable(): Boolean {
        val runtimeDir = envProvider("XDG_RUNTIME_DIR") ?: return false
        return java.io.File("$runtimeDir/pipewire-0").exists()
    }

    fun avx2Supported(): Boolean {
        if (!isLinux()) return true  // assume capable on non-Linux
        return try {
            java.io.File("/proc/cpuinfo").readText().contains("avx2")
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * Singleton using real [System.getProperty] and [System.getenv].
 * All production code should use this object; tests should use [Platform] directly.
 */
object PlatformInfo : Platform()
