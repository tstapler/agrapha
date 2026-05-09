package com.meetingnotes.domain.plugin

import com.meetingnotes.plugin.DictationMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UNIT-3-1-01 through UNIT-3-1-02
 */
class DictationModeTest {

    // ── UNIT-3-1-01 ──────────────────────────────────────────────────────────
    @Test
    fun `DictationMode PUSH_TO_TALK round-trips through JSON`() {
        val encoded = Json.encodeToString(DictationMode.PUSH_TO_TALK)
        val decoded = Json.decodeFromString<DictationMode>(encoded)
        assertEquals(DictationMode.PUSH_TO_TALK, decoded)
    }

    // ── UNIT-3-1-02 ──────────────────────────────────────────────────────────
    @Test
    fun `all three DictationMode values survive JSON serialization`() {
        DictationMode.entries.forEach { mode ->
            val encoded = Json.encodeToString(mode)
            val decoded = Json.decodeFromString<DictationMode>(encoded)
            assertEquals(mode, decoded, "Round-trip failed for $mode")
        }
    }
}
