package com.squareup.stoic.android.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.squareup.stoic.common.JvmtiAttachOptions
import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.optionsJsonFromStoicDir
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.waitFifo
import com.squareup.stoic.threadlocals.stoic
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

val serverRunning = AtomicBoolean(false)
fun ensureServer(stoicDir: String) {
  if (serverRunning.compareAndSet(false, true)) {
    startServer(stoicDir)
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
    if (options.stoicVersion != STOIC_PROTOCOL_VERSION) {
      throw Exception("Mismatched versions: ${options.stoicVersion} and $STOIC_PROTOCOL_VERSION")
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
        Log.d("stoic", "wrote to $fifo")
      } catch (e: IOException) {
        Log.e("stoic", "Failed to write to $fifo", e)
        throw e
      }
    }

    while (true) {
      val socket = server.accept()
      thread (name = "stoic-plugin") {
        try {
          StoicPlugin(stoicDir, mapOf(), socket.inputStream, socket.outputStream).pluginMain()
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