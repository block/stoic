package com.squareup.stoic.demoapp.withsdk.plugins

import android.content.Context
import com.squareup.stoic.plugin.StoicConfig
import com.squareup.stoic.plugin.StoicPlugin

/**
 * Stoic configuration for the demo app.
 *
 * This class is declared in AndroidManifest.xml and will be automatically
 * discovered by Stoic when it attaches (via JVMTI or BroadcastReceiver).
 */
class DemoStoicConfig : StoicConfig {
    override fun getEmbeddedPlugins(context: Context): Map<String, Lazy<StoicPlugin>> {
        return mapOf(
            "demo-embedded" to lazy { DemoEmbeddedPlugin() }
        )
    }
}
