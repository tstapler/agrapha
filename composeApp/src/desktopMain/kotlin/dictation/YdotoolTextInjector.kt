package com.meetingnotes.dictation

/**
 * Injectable factory for [ProcessBuilder] — enables subprocess mocking in unit tests
 * without installing ydotool on CI runners.
 */
fun interface ProcessBuilderFactory {
    fun create(vararg command: String): ProcessBuilder
}

/** Production implementation: delegates directly to [ProcessBuilder]. */
object DefaultProcessBuilderFactory : ProcessBuilderFactory {
    override fun create(vararg command: String): ProcessBuilder = ProcessBuilder(*command)
}

/**
 * [TextInjector] implementation that shells out to `ydotool type`.
 *
 * ydotool works on both Wayland and X11 but requires a running `ydotoold` daemon.
 * [checkStatus] detects all three failure modes without requiring a real subprocess
 * when a [ProcessBuilderFactory] mock is injected.
 *
 * @param processBuilderFactory injectable for testing; defaults to [DefaultProcessBuilderFactory].
 * @param socketPath path to ydotoold socket; injectable for testing.
 */
class YdotoolTextInjector(
    private val processBuilderFactory: ProcessBuilderFactory = DefaultProcessBuilderFactory,
    private val socketPath: String = "/tmp/.ydotool_socket",
) : TextInjector {

    enum class YdotoolStatus { NOT_INSTALLED, DAEMON_NOT_RUNNING, OK }

    /**
     * Check whether ydotool is installed and its daemon is reachable.
     *
     * Order:
     *  1. `which ydotool` → NOT_INSTALLED if exit code != 0
     *  2. `pgrep -x ydotoold` exits 0 OR [socketPath] exists → OK
     *  3. Otherwise → DAEMON_NOT_RUNNING
     */
    fun checkDetailedStatus(): YdotoolStatus {
        // 1. Is ydotool installed?
        val whichExit = runProcess("which", "ydotool")
        if (whichExit != 0) return YdotoolStatus.NOT_INSTALLED

        // 2. Is the daemon running?
        val pgrepExit = runProcess("pgrep", "-x", "ydotoold")
        if (pgrepExit == 0) return YdotoolStatus.OK

        // 3. Socket file as fallback
        if (java.io.File(socketPath).exists()) return YdotoolStatus.OK

        return YdotoolStatus.DAEMON_NOT_RUNNING
    }

    override fun checkStatus(): TextInjector.Status = when (checkDetailedStatus()) {
        YdotoolStatus.NOT_INSTALLED     -> TextInjector.Status.NOT_INSTALLED
        YdotoolStatus.DAEMON_NOT_RUNNING -> TextInjector.Status.DAEMON_NOT_RUNNING
        YdotoolStatus.OK                -> TextInjector.Status.OK
    }

    override fun inject(text: String): Result<Unit> {
        if (checkDetailedStatus() != YdotoolStatus.OK) {
            return Result.failure(
                TextInjectorUnavailableException("ydotool is not available: ${checkDetailedStatus()}")
            )
        }
        val sanitized = sanitize(text)
        return runInject("ydotool", "type", "--clearmodifiers", "--", sanitized)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /**
     * Strip characters below 0x20 except newline (0x0A) and null bytes.
     * Does not shell-escape — ProcessBuilder varargs form is used to avoid shell injection.
     */
    internal fun sanitize(text: String): String =
        text.filter { it.code >= 0x20 || it == '\n' }

    private fun runProcess(vararg command: String): Int = try {
        processBuilderFactory.create(*command)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    } catch (_: Exception) {
        1  // treat exception as failure
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
            Result.failure(Exception("ydotool exited with code $exitCode: $output"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
