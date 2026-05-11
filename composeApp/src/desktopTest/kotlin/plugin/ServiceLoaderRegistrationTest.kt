package com.meetingnotes.plugin

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UNIT-5-5-01 through UNIT-5-5-02
 * INTG-03-03
 */
class ServiceLoaderRegistrationTest {

    // ── UNIT-5-5-01 ──────────────────────────────────────────────────────────
    @Test
    fun `ServiceLoader finds DictationPlugin via META-INF services file`() {
        val plugins = java.util.ServiceLoader
            .load(SpeechOutputPlugin::class.java)
            .toList()

        assertTrue(
            plugins.isNotEmpty(),
            "ServiceLoader must find at least one SpeechOutputPlugin"
        )
    }

    // ── UNIT-5-5-02 ──────────────────────────────────────────────────────────
    @Test
    fun `discovered plugin has id com_agrapha_dictation`() {
        val plugins = java.util.ServiceLoader
            .load(SpeechOutputPlugin::class.java)
            .toList()

        val dictation = plugins.firstOrNull { it.id == "com.agrapha.dictation" }
        assertNotNull(dictation, "DictationPlugin must be discoverable with id 'com.agrapha.dictation'")
    }
}
