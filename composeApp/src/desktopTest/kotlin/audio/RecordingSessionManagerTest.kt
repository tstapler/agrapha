package com.meetingnotes.audio

import com.meetingnotes.data.FileStorageService
import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.createInMemoryDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [RecordingSessionManager].
 *
 * Audio sources (mic + JNI system audio) are unavailable in CI, so both channels
 * produce silence — this still exercises the WAV structure and DB persistence paths.
 */
class RecordingSessionManagerTest {

    private lateinit var tempDir: File
    private lateinit var repo: MeetingRepository
    private lateinit var storage: FileStorageService
    private lateinit var manager: RecordingSessionManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("rsm-test").toFile()
        tempDir.resolve("recordings").mkdirs()

        repo = MeetingRepository(createInMemoryDatabase())

        storage = mockk<FileStorageService>()
        every { storage.getAudioFilePath(any()) } answers {
            tempDir.resolve("recordings/${firstArg<String>()}.wav").absolutePath
        }

        manager = RecordingSessionManager(repo, storage)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    // ── S6-UNIT-04: Clean Stop ─────────────────────────────────────────────────

    @Test
    fun `stopRecording writes stereo WAV and persists Meeting to DB`() {
        val meetingId = manager.startRecording(scope)

        // Allow coroutines to start; audio sources will fail silently
        Thread.sleep(200)

        val (meeting, _) = runBlocking { manager.stopRecording() }

        assertNotNull(meeting)
        assertEquals(meetingId, meeting!!.id)
        assertTrue(File(meeting.audioFilePath).exists(), "Stereo WAV file must exist")
        assertNotNull(repo.getMeetingById(meetingId), "Meeting must be persisted in DB")
    }

    // ── S6-UNIT-03: Duration Tracking ─────────────────────────────────────────

    @Test
    fun `durationMs increases while recording`() {
        assertEquals(0L, manager.durationMs.value, "durationMs should start at 0")

        manager.startRecording(scope)

        // Wait for at least two tick intervals (tick every 500ms)
        Thread.sleep(700)

        assertTrue(manager.durationMs.value > 0, "durationMs should be positive after 700ms")

        runBlocking { manager.stopRecording() }
    }

    @Test
    fun `durationMs resets to zero after stopRecording`() {
        manager.startRecording(scope)
        Thread.sleep(600)
        assertTrue(manager.durationMs.value > 0)

        runBlocking { manager.stopRecording() }

        assertEquals(0L, manager.durationMs.value, "durationMs must reset to 0 after stop")
    }

    // ── S6-UNIT-02: Stereo Channel Assignment ─────────────────────────────────

    @Test
    fun `output WAV is stereo with channel 0 as mic and channel 1 as system`() {
        manager.startRecording(scope)
        Thread.sleep(200)

        val (meeting, _) = runBlocking { manager.stopRecording() }
        assertNotNull(meeting)

        val wavFile = File(meeting!!.audioFilePath)
        assertTrue(wavFile.exists(), "WAV file must exist")
        assertTrue(wavFile.length() >= 44, "WAV file must have at least a header")

        // WAV header: bytes 22-23 = NumChannels (little-endian 16-bit)
        val bytes = wavFile.readBytes()
        val channels = ((bytes[23].toInt() and 0xFF) shl 8) or (bytes[22].toInt() and 0xFF)
        assertEquals(2, channels, "Output WAV must be stereo (2 channels); ch0=mic, ch1=system")

        // Both channels will be silence (no real audio sources in CI) —
        // the key assertion is the structural stereo layout.
        val numDataBytes = bytes.size - 44
        assertEquals(0, numDataBytes % 4, "Stereo 16-bit frames must be 4 bytes each")
    }
}
