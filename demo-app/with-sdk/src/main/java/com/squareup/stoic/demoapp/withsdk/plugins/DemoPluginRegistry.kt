package com.squareup.stoic.demoapp.withsdk.plugins

import android.content.Context
import com.squareup.stoic.plugin.StoicPlugin
import com.squareup.stoic.plugin.StoicPluginRegistry

/**
 * Registry for embedded Stoic plugins in the demo app.
 *
 * This class is declared in AndroidManifest.xml and will be automatically
 * discovered by Stoic when it attaches (via JVMTI or BroadcastReceiver).
 */
class DemoPluginRegistry : StoicPluginRegistry {
    override fun getPlugins(context: Context): Map<String, StoicPlugin> {
        return mapOf(
            "demo-embedded" to DemoEmbeddedPlugin()
        )
    }
}
