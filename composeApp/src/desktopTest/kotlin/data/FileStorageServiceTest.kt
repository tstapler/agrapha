package com.meetingnotes.data

import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider
import com.meetingnotes.domain.model.WhisperModelSize
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2-UNIT-05: ensureDirectoriesExist creates required subdirectories.
 * S2-UNIT-06: saveAudioBytes / loadAudioBytes round-trip.
 * S2-UNIT-07: AppSettings has correct defaults.
 */
class FileStorageServiceTest {

    private lateinit var tmpDir: File
    private val originalHome = System.getProperty("user.home")

    @Before
    fun setUp() {
        tmpDir = createTempDir("meeting-notes-test-")
        System.setProperty("user.home", tmpDir.absolutePath)
    }

    @After
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        tmpDir.deleteRecursively()
    }

    // ── S2-UNIT-05 ────────────────────────────────────────────────────────────

    @Test
    fun `ensureDirectoriesExist creates db, recordings and models subdirectories`() {
        val service = FileStorageService()
        service.ensureDirectoriesExist()

        val base = File(tmpDir, ".local/share/meeting-notes")
        assertTrue(File(base, "db").isDirectory, "db dir must exist")
        assertTrue(File(base, "recordings").isDirectory, "recordings dir must exist")
        assertTrue(File(base, "models").isDirectory, "models dir must exist")
    }

    @Test
    fun `ensureDirectoriesExist is idempotent`() {
        val service = FileStorageService()
        service.ensureDirectoriesExist()
        service.ensureDirectoriesExist()  // second call must not throw

        val base = File(tmpDir, ".local/share/meeting-notes")
        assertTrue(File(base, "db").isDirectory)
    }

    // ── S2-UNIT-06 ────────────────────────────────────────────────────────────

    @Test
    fun `saveAudioBytes and loadAudioBytes round-trip`() {
        val service = FileStorageService()
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x42)

        service.saveAudioBytes("mtg-001", payload)
        val loaded = service.loadAudioBytes("mtg-001")

        assertNotNull(loaded)
        assertTrue(loaded.contentEquals(payload), "Loaded bytes must equal saved bytes")
    }

    @Test
    fun `loadAudioBytes returns null when file does not exist`() {
        val service = FileStorageService()
        assertNull(service.loadAudioBytes("nonexistent-id"))
    }

    @Test
    fun `getAudioFilePath returns path under recordings subdirectory`() {
        val service = FileStorageService()
        val path = service.getAudioFilePath("mtg-abc")
        assertTrue(path.endsWith("mtg-abc.wav"), "Audio path must end with <id>.wav; got: $path")
        assertTrue(path.contains("recordings"), "Audio path must be under recordings/; got: $path")
    }

    @Test
    fun `getModelsDir returns path under base directory`() {
        val service = FileStorageService()
        val dir = service.getModelsDir()
        assertTrue(dir.endsWith("models"), "Models dir must end with 'models'; got: $dir")
    }

    @Test
    fun `deleteAudioFile removes the wav file`() {
        val service = FileStorageService()
        service.saveAudioBytes("mtg-del", byteArrayOf(0x01, 0x02))
        assertNotNull(service.loadAudioBytes("mtg-del"), "File must exist before deletion")

        service.deleteAudioFile("mtg-del")

        assertNull(service.loadAudioBytes("mtg-del"), "File must be gone after deletion")
    }

    @Test
    fun `deleteAudioFile is a no-op when file does not exist`() {
        val service = FileStorageService()
        service.deleteAudioFile("never-existed")  // must not throw
    }

    // ── S2-UNIT-07 ────────────────────────────────────────────────────────────

    @Test
    fun `AppSettings defaults are correct`() {
        val settings = AppSettings()
        assertEquals(WhisperModelSize.SMALL, settings.whisperModelSize)
        assertEquals(LlmProvider.OLLAMA, settings.llmProvider)
        assertEquals("llama3.2", settings.llmModel)
        assertEquals("", settings.whisperModelPath)
        assertEquals("", settings.logseqWikiPath)
        assertNull(settings.llmApiKey)
    }
}
