package com.meetingnotes.plugin

/**
 * SPI contract for all dictation/speech-output plugins.
 *
 * Placed in commonMain so external plugin JARs can compile against a minimal
 * shared dependency (no platform-specific JNI or JVM-desktop imports).
 *
 * Plugin lifecycle:
 *  1. [isAvailable] — checked on load; unavailable plugins are shown as disabled
 *  2. [activate] — called when the user starts a dictation session
 *  3. [deactivate] — called when the session ends (may be called multiple times)
 *  4. [close] — called once before the plugin's classloader is closed; do cleanup here
 *
 * Plugin authors must ensure that [activate] and [deactivate] are safe to call from
 * a coroutine context, and that [close] does not block the calling thread for more
 * than a few hundred milliseconds.
 */
interface SpeechOutputPlugin {
    /** Stable reverse-DNS identifier, e.g. "com.agrapha.dictation". */
    val id: String

    /** Human-readable display name shown in Settings. */
    val name: String

    /** Semver string, e.g. "1.0.0". */
    val version: String

    /** Set of [DictationMode]s this plugin implements. */
    val supportedModes: Set<DictationMode>

    /**
     * Activate this plugin in [mode] with the given key/value [config].
     * Config keys are mode-specific (documented per plugin).
     * Must not throw; surface errors via [PluginException] logged/displayed by the host.
     */
    suspend fun activate(mode: DictationMode, config: Map<String, String>): Result<Unit>

    /**
     * Deactivate the plugin — stop any ongoing capture, injection, or UI.
     * Must not throw; idempotent.
     */
    suspend fun deactivate()

    /** True if this plugin can operate on the current platform/system configuration. */
    fun isAvailable(): Boolean
}

/**
 * Signals a plugin-originated error.
 * The host app catches these and displays them inline in Settings — it does not crash.
 */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)
