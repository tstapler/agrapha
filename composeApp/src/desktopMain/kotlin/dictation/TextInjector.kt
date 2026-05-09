package com.meetingnotes.dictation

/**
 * Abstraction for injecting text into the currently focused window.
 *
 * Implementations: [YdotoolTextInjector], [XdotoolTextInjector], [AutoDetectTextInjector].
 */
interface TextInjector {
    /** Three-state health of the injector tool and its required daemon. */
    enum class Status { OK, NOT_INSTALLED, DAEMON_NOT_RUNNING }

    /** Check whether this injector is operational. Never throws. */
    fun checkStatus(): Status

    /**
     * Type [text] into the currently focused window.
     *
     * Text is sanitized before injection (non-printable chars stripped).
     * @return [Result.success] on success; [Result.failure] wrapping a
     *   [TextInjectorUnavailableException] or subprocess error on failure.
     */
    fun inject(text: String): Result<Unit>

    /** Convenience wrapper over [checkStatus]. */
    fun isAvailable(): Boolean = checkStatus() == Status.OK
}

/**
 * Thrown when no [TextInjector] implementation is available on this system.
 */
class TextInjectorUnavailableException(msg: String) : Exception(msg)
