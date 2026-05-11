package com.meetingnotes.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UNIT-3-3-01 through UNIT-3-3-06
 */
class PluginLoaderTest {

    // ── UNIT-3-3-05 ──────────────────────────────────────────────────────────
    @Test
    fun `loadAll on empty directory returns empty list`() {
        val emptyDir = Files.createTempDirectory("plugins-empty").toFile()
        val loader = PluginLoader()
        val results = loader.loadAll(emptyDir)
        assertTrue(results.isEmpty())
        emptyDir.deleteRecursively()
    }

    // ── UNIT-3-3-06 ──────────────────────────────────────────────────────────
    @Test
    fun `loadAll on non-existent directory returns empty list`() {
        val nonExistent = File("/tmp/agrapha-plugins-does-not-exist-${System.nanoTime()}")
        val loader = PluginLoader()
        val results = loader.loadAll(nonExistent)
        assertTrue(results.isEmpty())
    }

    // ── UNIT-3-3-04 ──────────────────────────────────────────────────────────
    @Test
    fun `unload on unknown pluginId is a no-op`() {
        val loader = PluginLoader()
        runBlocking { loader.unload("com.example.nonexistent") }
        // Must not throw
    }

    // ── UNIT-3-3-01 (integration — ServiceLoader on classpath) ───────────────
    @Test
    fun `ServiceLoader discovers DictationPlugin on the classpath`() {
        // The META-INF/services file is in desktopMain resources; this test verifies
        // ServiceLoader finds the registration without loading from an external JAR.
        val plugins = java.util.ServiceLoader
            .load(SpeechOutputPlugin::class.java)
            .toList()

        assertTrue(
            plugins.isNotEmpty(),
            "ServiceLoader must find at least one SpeechOutputPlugin (DictationPlugin)"
        )

        val dictation = plugins.firstOrNull { it.id == "com.agrapha.dictation" }
        assertTrue(dictation != null, "DictationPlugin must be discoverable via ServiceLoader")
    }
}
