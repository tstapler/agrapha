package com.meetingnotes.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Which video conferencing platform is running. */
enum class MeetingSource(val displayName: String) {
    ZOOM("Zoom"),
    GOOGLE_MEET("Google Meet"),
}

/**
 * Represents an active video meeting detected on this machine.
 *
 * @param source Which platform the meeting is on.
 * @param title Human-readable meeting title (code for Google Meet, null for Zoom).
 * @param startedAt Wall-clock ms when the meeting was first detected; stable for the meeting lifetime.
 */
data class ActiveMeeting(
    val source: MeetingSource,
    val title: String?,
    val startedAt: Long = System.currentTimeMillis(),
) {
    /** Elapsed time since the meeting was first detected (ms). */
    val durationMs: Long get() = System.currentTimeMillis() - startedAt
}

/**
 * Polls every [pollIntervalMs] milliseconds to detect active video meetings on this Mac.
 *
 * Detection strategies:
 * - **Zoom**: checks for the `CptHost` child process, which Zoom spawns exclusively
 *   during active meetings (as opposed to `zoom.us`, which stays running between meetings).
 * - **Google Meet**: runs a brief AppleScript to look for "Meet - " in the window titles
 *   of common browsers. Requires Automation permission for each browser the first time it
 *   is queried. Falls back gracefully if the browser is not running or permission is denied.
 *
 * The [activeMeeting] flow emits `null` when no meeting is detected. Once a meeting is
 * detected, its [ActiveMeeting.startedAt] is kept stable so callers can compute duration.
 *
 * Lifecycle: create once at app startup; call [close] on exit.
 */
class MeetingDetector(private val pollIntervalMs: Long = 5_000L) : Closeable {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _activeMeeting = MutableStateFlow<ActiveMeeting?>(null)

    /** Non-null while a meeting is in progress; updated every [pollIntervalMs] ms. */
    val activeMeeting: StateFlow<ActiveMeeting?> = _activeMeeting.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                _activeMeeting.value = detectMeeting(_activeMeeting.value)
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Detect the current meeting state.
     * [previous] is passed so we can preserve [ActiveMeeting.startedAt] across polls.
     */
    private suspend fun detectMeeting(previous: ActiveMeeting?): ActiveMeeting? {
        // Zoom: synchronous process-list check (fast)
        val zoomActive = checkZoomActive()
        if (zoomActive) {
            return if (previous?.source == MeetingSource.ZOOM) previous
            else ActiveMeeting(source = MeetingSource.ZOOM, title = null)
        }

        // Google Meet: osascript window-title check (async, may be slow)
        val meetTitle = withContext(Dispatchers.IO) { checkGoogleMeetTitle() }
        if (meetTitle != null) {
            return if (previous?.source == MeetingSource.GOOGLE_MEET && previous.title == meetTitle) previous
            else ActiveMeeting(
                source = MeetingSource.GOOGLE_MEET,
                title = meetTitle,
                startedAt = if (previous?.source == MeetingSource.GOOGLE_MEET) previous.startedAt
                            else System.currentTimeMillis(),
            )
        }

        return null
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private fun checkZoomActive(): Boolean =
        ProcessHandle.allProcesses().anyMatch { ph ->
            val cmd = ph.info().command().orElse("")
            cmd.endsWith("/CptHost") || cmd == "CptHost"
        }

    // ── Google Meet ───────────────────────────────────────────────────────────

    /**
     * Returns the Google Meet meeting code/title (e.g. "abc-defg-hij") if a Meet tab
     * is the front window in any supported browser, or null if none is found.
     *
     * Uses AppleScript via `osascript`. Requires Automation permission for each browser
     * (macOS prompts once per app). Returns null on any error rather than throwing.
     */
    private fun checkGoogleMeetTitle(): String? {
        val browsers = listOf("Google Chrome", "Chromium", "Microsoft Edge", "Arc", "Brave Browser")
        for (browser in browsers) {
            try {
                val script = """
                    tell application "System Events"
                        if not (exists process "$browser") then return ""
                    end tell
                    tell application "$browser"
                        set matchTitle to ""
                        repeat with w in windows
                            set t to title of w
                            if t contains "Meet - " then
                                set matchTitle to t
                                exit repeat
                            end if
                        end repeat
                        return matchTitle
                    end tell
                """.trimIndent()

                val process = ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()

                if (output.contains("Meet - ")) {
                    // Title is like "Meet - abc-defg-hij" or "abc-defg-hij - Meet"
                    val code = output.split(" - ").firstOrNull { it != "Meet" }?.trim()
                    return code?.ifBlank { null }
                }
            } catch (_: Exception) {
                // Browser not running, osascript not available, or permission denied — skip
            }
        }
        return null
    }

    override fun close() {
        scope.cancel()
    }
}
