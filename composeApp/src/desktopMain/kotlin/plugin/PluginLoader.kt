package com.meetingnotes.plugin

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Result of attempting to load a single plugin JAR.
 */
sealed class PluginLoadResult {
    data class Success(
        val plugin: SpeechOutputPlugin,
        val jarPath: String,
    ) : PluginLoadResult()

    data class Failure(
        val jarPath: String,
        val error: Throwable,
    ) : PluginLoadResult()
}

/**
 * Loads [SpeechOutputPlugin] implementations from a directory of JAR files.
 *
 * Each JAR gets its own child-first [URLClassLoader] for isolation — a crashing plugin
 * cannot affect the classloader state of other plugins or the host application.
 *
 * Memory leak prevention: callers must call [unload] when a plugin is disabled so the
 * [URLClassLoader] is closed and its classes can be garbage-collected.
 */
class PluginLoader(
    private val parentClassLoader: ClassLoader = PluginLoader::class.java.classLoader,
) {
    private val loadedPlugins = mutableMapOf<String, Pair<SpeechOutputPlugin, URLClassLoader>>()

    /**
     * Scan [pluginDir] for JAR files and load all [SpeechOutputPlugin] implementations.
     *
     * @param pluginDir Directory to scan; returns an empty list if absent or not a directory.
     * @return One [PluginLoadResult] per discovered plugin registration.
     *         A single JAR may contain multiple plugins (each [SpeechOutputPlugin] entry in
     *         its META-INF/services file produces a separate result entry).
     */
    fun loadAll(pluginDir: File): List<PluginLoadResult> {
        if (!pluginDir.exists() || !pluginDir.isDirectory) return emptyList()

        val jars = pluginDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?: return emptyList()

        val results = mutableListOf<PluginLoadResult>()

        for (jar in jars) {
            val jarPath = jar.absolutePath
            try {
                // Child-first URLClassLoader: plugin classes override host-app versions.
                val loader = object : URLClassLoader(
                    arrayOf(jar.toURI().toURL()),
                    parentClassLoader
                ) {
                    override fun loadClass(name: String, resolve: Boolean): Class<*> {
                        // Try loading from this JAR first (child-first delegation).
                        synchronized(getClassLoadingLock(name)) {
                            var c = findLoadedClass(name)
                            if (c == null) {
                                c = try { findClass(name) } catch (_: ClassNotFoundException) { null }
                            }
                            if (c == null) {
                                c = parent.loadClass(name)
                            }
                            if (resolve) resolveClass(c)
                            return c
                        }
                    }
                }

                val serviceLoader = ServiceLoader.load(SpeechOutputPlugin::class.java, loader)
                val pluginsInJar = try {
                    serviceLoader.toList()
                } catch (e: Throwable) {
                    results += PluginLoadResult.Failure(jarPath, e)
                    loader.close()
                    continue
                }

                if (pluginsInJar.isEmpty()) {
                    // No META-INF/services entry — not a plugin JAR; close the loader.
                    loader.close()
                    continue
                }

                for (plugin in pluginsInJar) {
                    loadedPlugins[plugin.id] = Pair(plugin, loader)
                    results += PluginLoadResult.Success(plugin, jarPath)
                }

            } catch (e: Throwable) {
                results += PluginLoadResult.Failure(jarPath, e)
            }
        }

        return results
    }

    /**
     * Return all currently loaded plugins.
     */
    fun loadedPlugins(): List<SpeechOutputPlugin> =
        loadedPlugins.values.map { it.first }

    /**
     * Deactivate and close the classloader for the plugin with the given [pluginId].
     * After this call the plugin's classes may be garbage-collected.
     *
     * No-op if [pluginId] is not currently loaded.
     */
    suspend fun unload(pluginId: String) {
        val (plugin, loader) = loadedPlugins.remove(pluginId) ?: return
        try {
            plugin.deactivate()
        } catch (e: Throwable) {
            System.err.println("[PluginLoader] deactivate() threw for $pluginId: $e")
        }
        try {
            loader.close()
            System.err.println("[PluginLoader] Plugin unloaded: $pluginId")
        } catch (e: Throwable) {
            System.err.println("[PluginLoader] Failed to close classloader for $pluginId: $e")
        }
    }

    /**
     * Default plugin directory: ~/.config/agrapha/plugins/
     */
    companion object {
        val defaultPluginDir: File
            get() = File(System.getProperty("user.home"), ".config/agrapha/plugins")
    }
}
