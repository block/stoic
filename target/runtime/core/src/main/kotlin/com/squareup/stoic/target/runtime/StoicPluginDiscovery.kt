package com.squareup.stoic.target.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.squareup.stoic.plugin.StoicPlugin
import com.squareup.stoic.plugin.StoicPluginRegistry

/**
 * Discovers embedded Stoic plugins by reading AndroidManifest.xml meta-data.
 */
object StoicPluginDiscovery {
    private const val TAG = "StoicPluginDiscovery"
    private const val METADATA_KEY = "com.squareup.stoic.plugin.registry"

    /**
     * Discovers all plugins registered via AndroidManifest meta-data.
     *
     * Looks for `<meta-data android:name="com.squareup.stoic.plugin.registry" android:value="..."/>` entries,
     * instantiates each registry class, and collects all plugins.
     *
     * @param context Application context
     * @return Map of plugin names to plugin instances
     */
    fun discoverPlugins(context: Context): Map<String, StoicPlugin> {
        val plugins = mutableMapOf<String, StoicPlugin>()

        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )

            val metaData = appInfo.metaData
            if (metaData == null) {
                Log.d(TAG, "No meta-data found in AndroidManifest")
                return emptyMap()
            }

            // Collect all registry class names
            val registryClasses = mutableListOf<String>()
            for (key in metaData.keySet()) {
                if (key.startsWith(METADATA_KEY)) {
                    val value = metaData.getString(key)
                    if (value != null) {
                        registryClasses.add(value)
                        Log.d(TAG, "Found plugin registry: $value")
                    }
                }
            }

            // Load and instantiate each registry
            for (className in registryClasses) {
                try {
                    val clazz = Class.forName(className)
                    val instance = clazz.getDeclaredConstructor().newInstance()

                    if (instance !is StoicPluginRegistry) {
                        Log.e(TAG, "Class $className does not implement StoicPluginRegistry")
                        continue
                    }

                    val registryPlugins = instance.getPlugins(context)
                    Log.d(TAG, "Registry $className provided ${registryPlugins.size} plugins: ${registryPlugins.keys}")

                    // Merge plugins (later registries can override earlier ones)
                    for ((name, plugin) in registryPlugins) {
                        if (plugins.containsKey(name)) {
                            Log.w(TAG, "Plugin name '$name' registered multiple times - using last registration")
                        }
                        plugins[name] = plugin
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load plugin registry: $className", e)
                }
            }

            Log.i(TAG, "Discovered ${plugins.size} total plugins: ${plugins.keys}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover plugins", e)
        }

        return plugins
    }
}
