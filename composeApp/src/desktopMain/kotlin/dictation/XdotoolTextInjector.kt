package com.meetingnotes.dictation

/**
 * [TextInjector] implementation that shells out to `xdotool type`.
 *
 * xdotool only works on X11 (or XWayland when DISPLAY is set alongside WAYLAND_DISPLAY).
 * On pure Wayland sessions (WAYLAND_DISPLAY set, DISPLAY absent) [checkStatus] returns
 * [TextInjector.Status.DAEMON_NOT_RUNNING] so [AutoDetectTextInjector] will skip this backend.
 *
 * @param processBuilderFactory injectable for testing.
 * @param envProvider injectable environment variable reader; defaults to [System.getenv].
 */
class XdotoolTextInjector(
    private val processBuilderFactory: ProcessBuilderFactory = DefaultProcessBuilderFactory,
    private val envProvider: (String) -> String? = { System.getenv(it) },
) : TextInjector {

    /**
     * Returns [TextInjector.Status.OK] when:
     *  - `xdotool` is installed (which xdotool exits 0)
     *  - AND the session is X11 or XWayland (DISPLAY env var is set)
     *
     * Returns [TextInjector.Status.DAEMON_NOT_RUNNING] when on pure Wayland
     * (WAYLAND_DISPLAY set, DISPLAY absent) — even if xdotool is installed.
     *
     * Returns [TextInjector.Status.NOT_INSTALLED] when xdotool is absent.
     */
    override fun checkStatus(): TextInjector.Status {
        val display = envProvider("DISPLAY")
        val waylandDisplay = envProvider("WAYLAND_DISPLAY")

        // Pure Wayland: xdotool would produce no output
        if (waylandDisplay != null && display == null) {
            return TextInjector.Status.DAEMON_NOT_RUNNING
        }

        // Check installation
        val whichExit = runProcess("which", "xdotool")
        if (whichExit != 0) return TextInjector.Status.NOT_INSTALLED

        return TextInjector.Status.OK
    }

    override fun inject(text: String): Result<Unit> {
        if (checkStatus() != TextInjector.Status.OK) {
            return Result.failure(
                TextInjectorUnavailableException("xdotool is not available on this session")
            )
        }
        val sanitized = sanitize(text)
        return runInject("xdotool", "type", "--clearmodifiers", "--", sanitized)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /** Strip non-printable chars below 0x20 except newline. */
    internal fun sanitize(text: String): String =
        text.filter { it.code >= 0x20 || it == '\n' }

    private fun runProcess(vararg command: String): Int = try {
        processBuilderFactory.create(*command)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    } catch (_: Exception) {
        1
    }

    private fun runInject(vararg command: String): Result<Unit> = try {
        val process = processBuilderFactory.create(*command)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode == 0) {
            Result.success(Unit)
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            Result.failure(Exception("xdotool exited with code $exitCode: $output"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
