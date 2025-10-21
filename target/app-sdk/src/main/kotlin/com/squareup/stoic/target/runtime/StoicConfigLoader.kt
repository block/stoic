package com.squareup.stoic.target.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.squareup.stoic.plugin.StoicConfig
import com.squareup.stoic.plugin.StoicPlugin

/**
 * Loads Stoic configuration by reading AndroidManifest.xml meta-data.
 */
object StoicConfigLoader {
    private const val TAG = "StoicConfigLoader"
    private const val METADATA_KEY = "com.squareup.stoic.config"

    private val DEFAULT_CONFIG = object : StoicConfig {
        override fun getEmbeddedPlugins(context: Context): Map<String, Lazy<StoicPlugin>> = emptyMap()
    }

    /**
     * Loads the Stoic config registered via AndroidManifest meta-data.
     *
     * Looks for `<meta-data android:name="com.squareup.stoic.config" android:value="..."/>` entry,
     * and instantiates the config class.
     *
     * @param context Application context
     * @return StoicConfig instance, or a default config if not found or invalid
     */
    fun loadConfig(context: Context): StoicConfig {
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )

            val metaData = appInfo.metaData
            if (metaData == null) {
                Log.d(TAG, "No meta-data found in AndroidManifest")
                return DEFAULT_CONFIG
            }

            // Look for the config class
            val configClassName = metaData.getString(METADATA_KEY)
            if (configClassName == null) {
                Log.d(TAG, "No Stoic config found in AndroidManifest")
                return DEFAULT_CONFIG
            }

            Log.d(TAG, "Found Stoic config: $configClassName")

            // Load and instantiate the config
            val clazz = Class.forName(configClassName)
            val instance = clazz.getDeclaredConstructor().newInstance()

            if (instance !is StoicConfig) {
                Log.e(TAG, "Class $configClassName does not implement StoicConfig")
                return DEFAULT_CONFIG
            }

            Log.i(TAG, "Loaded Stoic config: $configClassName")
            return instance
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Stoic config", e)
            return DEFAULT_CONFIG
        }
    }
}
