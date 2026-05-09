@file:OptIn(ExperimentalMaterial3Api::class)

package com.meetingnotes.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetingnotes.plugin.PluginLoadResult

/**
 * Settings section that displays loaded plugins with enable/disable toggles.
 *
 * - [PluginLoadResult.Success] → plugin name + version + toggle
 * - [PluginLoadResult.Failure] → JAR path + error message in error color
 * - Empty [results] + [pluginDirExists] == false → "No plugins installed" hint
 */
@Composable
fun PluginsSettingsSection(
    results: List<PluginLoadResult>,
    enabledPlugins: Map<String, Boolean>,
    pluginDirExists: Boolean,
    onToggle: (pluginId: String, enabled: Boolean) -> Unit,
    onUnload: (pluginId: String) -> Unit,
) {
    val successes = results.filterIsInstance<PluginLoadResult.Success>()
    val failures  = results.filterIsInstance<PluginLoadResult.Failure>()

    if (!pluginDirExists && results.isEmpty()) {
        Text(
            text = "No plugins installed. Drop JARs into ~/.config/agrapha/plugins/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (results.isEmpty()) {
        Text(
            text = "No plugins found in ~/.config/agrapha/plugins/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        successes.forEach { result ->
            PluginRow(
                result = result,
                enabled = enabledPlugins[result.plugin.id] != false,  // default enabled
                onToggle = { enabled ->
                    if (!enabled) onUnload(result.plugin.id)
                    onToggle(result.plugin.id, enabled)
                },
            )
        }
        failures.forEach { result ->
            PluginErrorRow(result)
        }
    }
}

@Composable
private fun PluginRow(
    result: PluginLoadResult.Success,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(result.plugin.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "v${result.plugin.version} · ${result.plugin.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Modes: ${result.plugin.supportedModes.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PluginErrorRow(result: PluginLoadResult.Failure) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Failed to load: ${result.jarPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                result.error.message ?: result.error::class.simpleName ?: "Unknown error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
