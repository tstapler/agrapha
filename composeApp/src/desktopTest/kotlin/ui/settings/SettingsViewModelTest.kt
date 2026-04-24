package com.meetingnotes.ui.settings

import com.meetingnotes.data.MeetingRepository
import com.meetingnotes.data.SettingsRepository
import com.meetingnotes.data.createInMemoryDatabase
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider
import com.meetingnotes.domain.model.WhisperModelSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SettingsViewModel].
 *
 * Covers S4-UNIT-11 (persistence round-trip) and S4-UNIT-12 (invalid wiki path).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        settingsRepo = SettingsRepository(createInMemoryDatabase())
    }

    // ── S4-UNIT-11: Persistence Round-Trip ────────────────────────────────────

    @Test
    fun `save persists all settings fields and a new ViewModel loads them back`() = runTest(UnconfinedTestDispatcher()) {
        val wikiDir = tempFolder.newFolder("wiki")
        val modelFile = tempFolder.newFile("ggml-tiny.bin")

        val expected = AppSettings(
            whisperModelPath = modelFile.absolutePath,
            whisperModelSize = WhisperModelSize.TINY,
            llmProvider = LlmProvider.OPENAI,
            llmModel = "gpt-4o",
            llmApiKey = "sk-test123",
            llmBaseUrl = "http://localhost:11434",
            logseqWikiPath = wikiDir.absolutePath,
        )

        // Save via first ViewModel
        val vm1 = SettingsViewModel(settingsRepo, this)
        vm1.onSettingsChange(expected)
        vm1.save()

        // Wait for save coroutine to complete
        val saved = vm1.state.first { it.saveSuccess }
        assertTrue(saved.saveSuccess)

        // Load via second ViewModel — no defaults should override
        val vm2 = SettingsViewModel(settingsRepo, this)
        val loaded = vm2.state.first { !it.loading }

        assertEquals(expected.whisperModelPath, loaded.settings.whisperModelPath)
        assertEquals(expected.whisperModelSize, loaded.settings.whisperModelSize)
        assertEquals(expected.llmProvider, loaded.settings.llmProvider)
        assertEquals(expected.llmModel, loaded.settings.llmModel)
        assertEquals(expected.llmApiKey, loaded.settings.llmApiKey)
        assertEquals(expected.logseqWikiPath, loaded.settings.logseqWikiPath)
    }

    // ── S4-UNIT-12: Invalid Wiki Path ─────────────────────────────────────────

    @Test
    fun `save is rejected when logseqWikiPath does not exist`() = runTest(UnconfinedTestDispatcher()) {
        val vm = SettingsViewModel(settingsRepo, this)
        vm.state.first { !it.loading } // wait for load

        val badSettings = AppSettings(
            logseqWikiPath = "/nonexistent/path/that/does/not/exist",
        )
        vm.onSettingsChange(badSettings)
        vm.save()

        val state = vm.state.value
        assertTrue("logseqWikiPath" in state.validationErrors, "Should have logseqWikiPath error")
        assertFalse(state.saveSuccess, "Save should not succeed with invalid wiki path")
    }

    @Test
    fun `save is rejected when llmApiKey is blank for non-Ollama provider`() = runTest(UnconfinedTestDispatcher()) {
        val wikiDir = tempFolder.newFolder("wiki2")

        val vm = SettingsViewModel(settingsRepo, this)
        vm.state.first { !it.loading }

        val settings = AppSettings(
            llmProvider = LlmProvider.ANTHROPIC,
            llmApiKey = null,
            logseqWikiPath = wikiDir.absolutePath,
        )
        vm.onSettingsChange(settings)
        vm.save()

        val state = vm.state.value
        assertTrue("llmApiKey" in state.validationErrors, "Should require API key for Anthropic")
        assertFalse(state.saveSuccess)
    }

    @Test
    fun `Ollama provider does not require an API key`() = runTest(UnconfinedTestDispatcher()) {
        val wikiDir = tempFolder.newFolder("wiki3")

        val vm = SettingsViewModel(settingsRepo, this)
        vm.state.first { !it.loading }

        val settings = AppSettings(
            llmProvider = LlmProvider.OLLAMA,
            llmApiKey = null,
            logseqWikiPath = wikiDir.absolutePath,
        )
        vm.onSettingsChange(settings)
        vm.save()

        val state = vm1State(vm)
        assertFalse("llmApiKey" in state.validationErrors, "Ollama should not require API key")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun vm1State(vm: SettingsViewModel) = vm.state.first { it.saveSuccess || it.validationErrors.isNotEmpty() }
}
