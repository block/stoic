package com.squareup.stoic.target.runtime

import android.content.Context
import android.util.Log

private const val TAG = "StoicContextProvider"

/**
 * Attempts to retrieve the application Context using reflection.
 *
 * This is needed when Stoic attaches via JVMTI and doesn't have a Context readily available. Uses
 * ActivityThread.currentApplication() which is available in all Android versions.
 *
 * @return Application Context, or null if unable to obtain it
 */
fun retrieveApplicationContextViaReflection(): Context? {
  return try {
    // Use reflection to access ActivityThread.currentApplication()
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
    val application = currentApplicationMethod.invoke(null)

    if (application != null) {
      Log.d(TAG, "Successfully obtained application context via ActivityThread")
      application as Context
    } else {
      Log.w(TAG, "ActivityThread.currentApplication() returned null")
      null
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to get application context via reflection", e)
    null
  }
}
