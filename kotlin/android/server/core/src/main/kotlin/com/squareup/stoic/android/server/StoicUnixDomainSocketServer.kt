package com.squareup.stoic.android.server

import android.net.LocalServerSocket
import android.util.Log
import com.squareup.stoic.common.JvmtiAttachOptions
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.optionsJsonFromStoicDir
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.waitFifo
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.concurrent.thread

class StoicUnixDomainSocketServer {
  companion object {
    val lock = Any()
    var isRunning: Boolean = false

    fun ensureRunning(stoicDir: String, async: Boolean) {
      if (async) {
        synchronized(lock) {
          if (isRunning) { return } else { isRunning = true }

          thread(isDaemon = true, name = "stoic-uds-server") {
            startServer(stoicDir)
          }
        }
      } else {
        synchronized(lock) {
          if (isRunning) { return } else { isRunning = true }
        }

        startServer(stoicDir)
      }
    }
  }
}

private fun startServer(stoicDir: String) {
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

    thread(name = "stoic-server") {
      val fifo = waitFifo(File(stoicDir))
      Log.d("stoic", "Letting the client know that we're up by writing to the $fifo")
      try {
        // It doesn't actually matter what we write - we just need to open it for writing
        fifo.outputStream().close()
        Log.d("stoic", "wrote to $fifo!!")
      } catch (e: IOException) {
        Log.e("stoic", "Failed to write to $fifo", e)
        throw e
      }
    }

    while (true) {
      Log.i("stoic", "waiting for connection")
      val socket = server.accept()
      Log.i("stoic", "received connection: $socket")
      thread (name = "stoic-plugin") {
        Log.i("stoic", "spawned thread for connection: $socket")
        try {
          StoicPluginServer(stoicDir, mapOf(), socket.inputStream, socket.outputStream).pluginMain()
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