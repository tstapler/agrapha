package com.meetingnotes.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * In-app audio player backed by [javax.sound.sampled.Clip].
 *
 * Loads a WAV file, plays/pauses it, supports seeking, and
 * emits the current playback position every ~100 ms via [positionMs].
 *
 * Call [close] when done — typically from a [androidx.compose.runtime.DisposableEffect]
 * in the owning screen composable.
 */
class AudioPlayer : Closeable {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clip: Clip? = null
    private var pollJob: Job? = null

    private val _positionMs = MutableStateFlow(0L)
    /** Current playback position in milliseconds, updated every ~100 ms. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    /** True while the clip is actively playing. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Total duration of the loaded audio in milliseconds. 0 if no clip is loaded. */
    val durationMs: Long
        get() = clip?.microsecondLength?.div(1000L) ?: 0L

    /**
     * Load the WAV file at [path] into memory.
     * Must be called from a background thread (performs file I/O).
     */
    fun load(path: String) {
        clip?.close()
        val newClip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(File(path)).use { stream ->
            newClip.open(stream)
        }
        newClip.addLineListener { event ->
            if (event.type == LineEvent.Type.STOP) {
                _isPlaying.value = false
                stopPolling()
            }
        }
        clip = newClip
        _positionMs.value = 0L
    }

    /** Toggle between play and pause. No-op if no clip is loaded. */
    fun playPause() {
        val c = clip ?: return
        if (c.isRunning) {
            c.stop()
            stopPolling()
        } else {
            c.start()
            _isPlaying.value = true
            startPolling()
        }
    }

    /**
     * Seek to [ms] milliseconds from the start of the recording.
     * Resumes playback automatically if the clip was already running.
     */
    fun seekTo(ms: Long) {
        val c = clip ?: return
        val wasPlaying = c.isRunning
        if (wasPlaying) c.stop()
        c.microsecondPosition = ms * 1000L
        _positionMs.value = ms
        if (wasPlaying) {
            c.start()
            _isPlaying.value = true
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(100)
                _positionMs.value = (clip?.microsecondPosition ?: 0L) / 1000L
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun close() {
        stopPolling()
        scope.cancel()
        clip?.stop()
        clip?.close()
        clip = null
    }
}
