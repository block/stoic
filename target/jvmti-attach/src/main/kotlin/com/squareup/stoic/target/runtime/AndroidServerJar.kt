package com.squareup.stoic.target.runtime

import android.util.Log
import com.squareup.stoic.StoicJvmti

@Suppress("unused")
fun main(stoicDir: String) {
  Log.i("stoic", "start of AndroidServerJarKt.main")

  // RegisterNatives happened before we were called, so we mark Jvmti as ready to use
  StoicJvmti.markInitialized()

  StoicUnixDomainSocketServer.ensureRunning(stoicDir = stoicDir)
}