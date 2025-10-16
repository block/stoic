package com.squareup.stoic.android.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.squareup.stoic.target.runtime.StoicUnixDomainSocketServer
import com.squareup.stoic.common.AttachVia
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.optionsJsonFromStoicDir
import java.io.File

class StoicBroadcastReceiver: BroadcastReceiver() {
  override fun onReceive(
    context: Context?,
    intent: Intent?
  ) {
    Log.i("stoic", "onReceive($context, $intent)")
    val stoicDir = context!!.getDir("stoic", Context.MODE_PRIVATE).absolutePath
    val optionsJsonPath = optionsJsonFromStoicDir(stoicDir)
    File(optionsJsonPath).writeText(
      "{\"stoicProtocolVersion\":$STOIC_PROTOCOL_VERSION, \"attachedVia\":\"${AttachVia.SDK.str}\"}"
    )
    StoicUnixDomainSocketServer.ensureRunning(stoicDir = stoicDir, async = true)
    Log.i("stoic", "started server")
  }
}