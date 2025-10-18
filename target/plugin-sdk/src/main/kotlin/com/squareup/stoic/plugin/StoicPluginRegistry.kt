package com.squareup.stoic.plugin

import android.content.Context

/**
 * Registry of embedded Stoic plugins.
 *
 * To make plugins available when Stoic attaches to your app, implement this interface
 * and declare it in your AndroidManifest.xml:
 *
 * ```xml
 * <application>
 *     <meta-data
 *         android:name="com.squareup.stoic.plugin.registry"
 *         android:value="com.example.MyPluginRegistry" />
 * </application>
 * ```
 *
 * Then implement the registry:
 *
 * ```kotlin
 * class MyPluginRegistry : StoicPluginRegistry {
 *     override fun getPlugins(context: Context): Map<String, StoicPlugin> {
 *         return mapOf(
 *             "my-plugin" to MyPlugin(context),
 *             "another-plugin" to AnotherPlugin()
 *         )
 *     }
 * }
 * ```
 *
 * When Stoic attaches (via JVMTI or BroadcastReceiver), it will:
 * 1. Read your app's AndroidManifest.xml
 * 2. Find all `com.squareup.stoic.plugin.registry` meta-data entries
 * 3. Instantiate each registry class
 * 4. Call [getPlugins] to discover available plugins
 *
 * The plugins can then be invoked with: `stoic PACKAGE PLUGIN_NAME ARGS`.
 */
interface StoicPluginRegistry {
    /**
     * Returns a map of plugin names to plugin instances.
     *
     * Plugin names should be lowercase and use hyphens for word separation (e.g., "my-plugin").
     * These names are used on the command line: `stoic PACKAGE PLUGIN_NAME`
     *
     * @param context The application context
     * @return Map of plugin name to plugin instance
     */
    fun getPlugins(context: Context): Map<String, StoicPlugin>
}
