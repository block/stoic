package com.squareup.stoic.plugin

/**
 * A Stoic plugin that can be embedded into an application.
 *
 * When embedded plugins are registered via [StoicPluginRegistry],
 * they become available when Stoic attaches to the app (either via JVMTI
 * or BroadcastReceiver).
 *
 * Example:
 * ```kotlin
 * class MyPlugin : StoicPlugin {
 *     override fun run(args: List<String>): Int {
 *         println("Hello from MyPlugin with args: $args")
 *         return 0
 *     }
 * }
 * ```
 *
 * @see StoicPluginRegistry
 */
interface StoicPlugin {
    /**
     * Executes the plugin with the given arguments.
     *
     * @param args Command-line arguments passed to the plugin
     * @return Exit code (0 for success, non-zero for failure)
     */
    fun run(args: List<String>): Int
}
