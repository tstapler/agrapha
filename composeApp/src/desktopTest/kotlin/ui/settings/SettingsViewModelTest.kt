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
import java.io.File
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

    // ── S4-UNIT-11b: Persistence Round-Trip — extended fields ─────────────────
    // Regression test: diarization, correction, transcription, and live-captions
    // settings were absent from SettingsRepository.load()/save() and did not
    // survive app restarts.

    @Test
    fun `save persists diarization, correction, transcription, and live-captions fields`() = runTest(UnconfinedTestDispatcher()) {
        val wikiDir = tempFolder.newFolder("wiki_extended")
        val expected = AppSettings(
            logseqWikiPath = wikiDir.absolutePath,
            diarizationEnabled = true,
            huggingFaceToken = "hf_test_token",
            diarizationMaxSpeakers = 4,
            diarizationBackend = "python",
            correctionEnabled = true,
            transcriptionBackend = "apple-speech",
            parakeetModelDir = "/models/parakeet",
            liveCaptionsEnabled = true,
            whisperInitialPrompt = "Custom prompt.",
            whisperNoSpeechThreshold = 0.5f,
            enabledPlugins = mapOf("com.agrapha.dictation" to false),
        )

        val vm1 = SettingsViewModel(settingsRepo, this)
        vm1.onSettingsChange(expected)
        vm1.save()
        vm1.state.first { it.saveSuccess }

        val vm2 = SettingsViewModel(settingsRepo, this)
        val loaded = vm2.state.first { !it.loading }.settings

        assertEquals(expected.diarizationEnabled, loaded.diarizationEnabled)
        assertEquals(expected.huggingFaceToken, loaded.huggingFaceToken)
        assertEquals(expected.diarizationMaxSpeakers, loaded.diarizationMaxSpeakers)
        assertEquals(expected.diarizationBackend, loaded.diarizationBackend)
        assertEquals(expected.correctionEnabled, loaded.correctionEnabled)
        assertEquals(expected.transcriptionBackend, loaded.transcriptionBackend)
        assertEquals(expected.parakeetModelDir, loaded.parakeetModelDir)
        assertEquals(expected.liveCaptionsEnabled, loaded.liveCaptionsEnabled)
        assertEquals(expected.whisperInitialPrompt, loaded.whisperInitialPrompt)
        assertEquals(expected.whisperNoSpeechThreshold, loaded.whisperNoSpeechThreshold)
        assertEquals(expected.enabledPlugins, loaded.enabledPlugins)
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

    // ── Path Expansion (via validate) ────────────────────────────────────────

    @Test
    fun `expandPath expands tilde to user home directory`() = runTest(UnconfinedTestDispatcher()) {
        val homeDir = System.getProperty("user.home")
        val wikiDir = tempFolder.newFolder("wiki_tilde")
        val tildeSubPath = "~/.agrapha_test_wiki"

        // Create the expanded directory to satisfy validation
        val expandedPath = File(homeDir, ".agrapha_test_wiki")
        expandedPath.mkdirs()
        try {
            val vm = SettingsViewModel(settingsRepo, this)
            vm.state.first { !it.loading }

            val settings = AppSettings(logseqWikiPath = tildeSubPath)
            vm.onSettingsChange(settings)
            vm.save()

            val state = vm1State(vm)
            assertFalse("logseqWikiPath" in state.validationErrors, "Tilde should be expanded to valid home path")
            assertTrue(state.saveSuccess, "Save should succeed when tilde expands to existing directory")
        } finally {
            expandedPath.deleteRecursively()
        }
    }

    @Test
    fun `expandPath expands dollar-brace environment variables`() = runTest(UnconfinedTestDispatcher()) {
        val testDir = tempFolder.newFolder("wiki_env")
        val envVarPath = "\${TEST_WIKI_EXPAND_PATH}"

        try {
            // Set environment variable for this test
            val envVarName = "TEST_WIKI_EXPAND_PATH"
            val originalEnv = System.getenv(envVarName)
            // Note: System.getenv() is read-only on most platforms, so we test with an existing var
            // For this test, we'll use the existing user.home property which is always available
            val homeDir = System.getProperty("user.home")
            val testSubDir = ".agrapha_test_brace_expand"
            val expandedDir = File(homeDir, testSubDir)
            expandedDir.mkdirs()

            try {
                val vm = SettingsViewModel(settingsRepo, this)
                vm.state.first { !it.loading }

                // Use HOME which is typically set as an env var
                val settings = AppSettings(logseqWikiPath = "\${HOME}/$testSubDir")
                vm.onSettingsChange(settings)
                vm.save()

                val state = vm1State(vm)
                assertFalse("logseqWikiPath" in state.validationErrors, "\${VAR} should expand environment variables")
                assertTrue(state.saveSuccess, "Save should succeed when \${VAR} expands to existing directory")
            } finally {
                expandedDir.deleteRecursively()
            }
        } finally {
            // Env vars cannot be unset, so we just proceed
        }
    }

    @Test
    fun `expandPath expands dollar environment variables without braces`() = runTest(UnconfinedTestDispatcher()) {
        val homeDir = System.getProperty("user.home")
        val testSubDir = ".agrapha_test_dollar_expand"
        val expandedDir = File(homeDir, testSubDir)
        expandedDir.mkdirs()

        try {
            val vm = SettingsViewModel(settingsRepo, this)
            vm.state.first { !it.loading }

            // Use HOME env var syntax: $VAR
            val settings = AppSettings(logseqWikiPath = "\$HOME/$testSubDir")
            vm.onSettingsChange(settings)
            vm.save()

            val state = vm1State(vm)
            assertFalse("logseqWikiPath" in state.validationErrors, "\$VAR should expand environment variables")
            assertTrue(state.saveSuccess, "Save should succeed when \$VAR expands to existing directory")
        } finally {
            expandedDir.deleteRecursively()
        }
    }

    @Test
    fun `expandPath leaves plain absolute paths unchanged`() = runTest(UnconfinedTestDispatcher()) {
        val plainPath = tempFolder.newFolder("wiki_plain").absolutePath

        val vm = SettingsViewModel(settingsRepo, this)
        vm.state.first { !it.loading }

        val settings = AppSettings(logseqWikiPath = plainPath)
        vm.onSettingsChange(settings)
        vm.save()

        val state = vm1State(vm)
        assertFalse("logseqWikiPath" in state.validationErrors, "Plain absolute paths should work unchanged")
        assertTrue(state.saveSuccess, "Save should succeed for plain absolute paths")
    }

    @Test
    fun `expandPath leaves unresolved environment variables as-is (validation still fails if path doesn't exist)`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = SettingsViewModel(settingsRepo, this)
            vm.state.first { !it.loading }

            // Use a nonexistent env var — after expansion, the literal string remains
            val settings = AppSettings(logseqWikiPath = "\$NONEXISTENT_VAR_THAT_WILL_NOT_EXIST/some/path")
            vm.onSettingsChange(settings)
            vm.save()

            val state = vm1State(vm)
            assertTrue("logseqWikiPath" in state.validationErrors, "Unresolved env vars should still fail validation")
            assertFalse(state.saveSuccess, "Save should fail when expanded path doesn't exist")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun vm1State(vm: SettingsViewModel) = vm.state.first { it.saveSuccess || it.validationErrors.isNotEmpty() }
}
