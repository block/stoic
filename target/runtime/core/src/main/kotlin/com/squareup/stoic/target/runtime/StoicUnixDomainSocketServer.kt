package com.squareup.stoic.target.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.util.Log
import com.squareup.stoic.common.JvmtiAttachOptions
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.optionsJsonFromStoicDir
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.serverUpFile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.concurrent.thread

class StoicUnixDomainSocketServer {
  companion object {
    val lock = Any()
    var isRunning: Boolean = false

    fun ensureRunning(stoicDir: String, async: Boolean, context: Context? = null) {
      if (async) {
        synchronized(lock) {
          if (isRunning) { return } else { isRunning = true }

          thread(isDaemon = true, name = "stoic-uds-server") {
            startServer(stoicDir, context)
          }
        }
      } else {
        synchronized(lock) {
          if (isRunning) { return } else { isRunning = true }
        }

        startServer(stoicDir, context)
      }
    }
  }
}

private fun startServer(stoicDir: String, context: Context?) {
  try {
    Log.d("stoic", "stoicDir: $stoicDir")
    val options = Json.decodeFromString(
      JvmtiAttachOptions.serializer(),
      File(optionsJsonFromStoicDir(stoicDir)).readText(UTF_8)
    )
    Log.d("stoic", "options: $options")
    if (options.stoicProtocolVersion != STOIC_PROTOCOL_VERSION) {
      throw Exception("Mismatched versions: ${options.stoicProtocolVersion} and $STOIC_PROTOCOL_VERSION")
    }

    // TODO: fix hack - get the pkg from something other than the dir
    val pkg = File(stoicDir).parentFile!!.name

    val server = LocalServerSocket(serverSocketName(pkg))
    val name = server.localSocketAddress.name
    val namespace = server.localSocketAddress.namespace
    Log.d("stoic", "localSocketAddress: ($name, $namespace)")

    // Signal that the server is ready
    val serverUp = serverUpFile(File(stoicDir))
    Log.d("stoic", "Letting the client know that we're up by creating $serverUp")
    try {
      serverUp.createNewFile()
      Log.d("stoic", "created $serverUp!!")
    } catch (e: IOException) {
      Log.e("stoic", "Failed to create $serverUp", e)
      throw e
    }

    while (true) {
      Log.i("stoic", "waiting for connection")

      // Note: If the process gets frozen
      // (https://source.android.com/docs/core/perf/cached-apps-freezer)
      // then the thread will get stuck here
      val socket = server.accept()

      Log.i("stoic", "received connection: $socket")
      thread (name = "stoic-plugin") {
        try {
          // Load config from AndroidManifest
          // Get context either from the parameter (BroadcastReceiver path) or
          // via reflection (JVMTI path)
          val appContext = context?.applicationContext ?: retrieveApplicationContextViaReflection()
          val embeddedPlugins = if (appContext != null) {
            Log.d("stoic", "Loading Stoic config...")
            StoicConfigLoader.loadConfig(appContext).getPlugins(appContext)
          } else {
            Log.w("stoic", "No context available - skipping config loading")
            emptyMap()
          }

          StoicPluginServer(
            stoicDir,
            options,
            embeddedPlugins,
            socket.inputStream,
            socket.outputStream
          ).pluginMain()
        } catch (e: Throwable) {
          Log.e("stoic", "unexpected", e)

          // We only close the socket in the event of an exception. Otherwise we want to give
          // the buffering thread(s) a chance to complete their transfers
          socket.close()

          // Bring down the process for non-Exception Throwables
          if (e !is Exception) {
            throw e
          }
        }
      }
    }
  } catch (e: Throwable) {
    Log.e("stoic", "unexpected", e)
    throw e;
  }
}
