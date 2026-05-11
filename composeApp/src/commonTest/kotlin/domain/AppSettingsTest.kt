package com.meetingnotes.domain

import com.meetingnotes.domain.model.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UNIT-3-4-01 through UNIT-3-4-03
 */
class AppSettingsTest {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── UNIT-3-4-01 ──────────────────────────────────────────────────────────
    @Test
    fun `enabledPlugins defaults to emptyMap when field absent from JSON`() {
        val json = """{"whisperModelPath":"","llmModel":"llama3.2"}"""
        val settings = lenientJson.decodeFromString<AppSettings>(json)
        assertEquals(emptyMap(), settings.enabledPlugins)
    }

    // ── UNIT-3-4-02 ──────────────────────────────────────────────────────────
    @Test
    fun `enabledPlugins round-trips through JSON`() {
        val original = AppSettings(
            enabledPlugins = mapOf(
                "com.agrapha.dictation" to true,
                "com.example.myplugin" to false,
            )
        )
        val encoded = Json.encodeToString(original)
        val decoded = Json.decodeFromString<AppSettings>(encoded)
        assertEquals(original.enabledPlugins, decoded.enabledPlugins)
        assertTrue(decoded.enabledPlugins["com.agrapha.dictation"] == true)
        assertFalse(decoded.enabledPlugins["com.example.myplugin"] == true)
    }

    // ── UNIT-3-4-03 ──────────────────────────────────────────────────────────
    @Test
    fun `old AppSettings JSON without enabledPlugins field does not throw`() {
        val oldJson = """
            {
              "whisperModelPath": "/path/to/model.bin",
              "whisperModelSize": "SMALL",
              "llmProvider": "OLLAMA",
              "llmModel": "llama3.2",
              "llmBaseUrl": "http://localhost:11434",
              "logseqWikiPath": "",
              "recordingRetentionDays": 30,
              "autoRecordZoom": false,
              "autoRecordGoogleMeet": false,
              "whisperInitialPrompt": "This is a software engineering meeting.",
              "whisperNoSpeechThreshold": 0.7,
              "diarizationEnabled": false,
              "huggingFaceToken": "",
              "diarizationMaxSpeakers": 0,
              "correctionEnabled": false
            }
        """.trimIndent()
        val settings = lenientJson.decodeFromString<AppSettings>(oldJson)
        assertEquals(emptyMap(), settings.enabledPlugins)
    }
}
