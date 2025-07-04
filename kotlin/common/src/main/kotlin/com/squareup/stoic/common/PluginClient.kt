package com.squareup.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

const val stoicDeviceDir = "/data/local/tmp/.stoic"
const val stoicDeviceSyncDir = "$stoicDeviceDir/sync"
const val stoicDeviceSyncPluginDir = "$stoicDeviceSyncDir/plugins"
const val shebangJarDir = "$stoicDeviceDir/shebang_jar"
const val stoicDemoAppWithoutSdk = "com.squareup.stoic.demoapp.withoutsdk"
const val runAsCompat = "$stoicDeviceSyncDir/bin/run-as-compat"

class PluginClient(
  dexJarInfo: Pair<File, String>?,
  val pluginParsedArgs: PluginParsedArgs,
  inputStream: InputStream,
  outputStream: OutputStream
) {
  val pluginDexJar = dexJarInfo?.first
  val pluginDexJarSha256Sum = dexJarInfo?.second
  val pluginName = pluginDexJar?.name?.removeSuffix(".dex.jar") ?: pluginParsedArgs.pluginModule
  val writer = MessageWriter(DataOutputStream(outputStream))
  val reader = MessageReader(DataInputStream(inputStream))
  var nextMessage: Any? = null

  fun handleVersionResult(verifyProtocolVersionRequestId: Int) {
    val succeeded = reader.consumeNext().let {
      check(it.isResponse && it.isComplete)
      check(it.requestId == verifyProtocolVersionRequestId)
      it.payload as Succeeded
    }
    logDebug { succeeded.message }
  }

  fun handleStartPluginResult(startPluginRequestId: Int) {
    val msg = reader.consumeNext().let {
      check(it.isResponse && it.isComplete)
      check(it.requestId == startPluginRequestId)
      it.payload
    }
    when (msg) {
      is Succeeded -> return // nothing more to do
      is Failed -> {
        when (msg.failureCode) {
          FailureCode.PLUGIN_MISSING.value -> {} // fall through to load plugin
          else -> throw IllegalStateException(msg.toString())
        }
      }
      else -> throw IllegalStateException("Unexpected message $msg")
    }

    // Need to load plugin
    if (pluginDexJar == null) {
      // TODO: Need to throw a sub-class of PithyException so we can catch it and try a tool instead
      throw PithyException(
        """
          Plugin not found: ${pluginParsedArgs.pluginModule}
          To list available plugins: stoic tool list
        """.trimIndent()
      )
    }

    val pluginBytes = pluginDexJar.readBytes()
    val loadPluginRequestId = writer.writeRequest(
        LoadPlugin(
        pluginName = pluginName,
        pluginSha = pluginDexJarSha256Sum!!,
      ),
      isComplete = false
    )
    writer.writeRequest(pluginDexJar.readBytes(), requestId = loadPluginRequestId)
    val startPluginRequestId = writer.writeRequest(
      StartPlugin(
        pluginName = pluginName,
        pluginSha = pluginDexJarSha256Sum,
        pluginArgs = pluginParsedArgs.pluginArgs,
        minLogLevel = minLogLevel.name,
        env = pluginParsedArgs.pluginEnvVars
      )
    )
    val loadPluginResult = reader.consumeNext().let {
      check(it.isResponse && it.isComplete)
      it.payload as Succeeded
    }
    logVerbose { loadPluginResult.toString() }

    val startPluginResult = reader.consumeNext().let {
      check(it.isResponse && it.isComplete)
      it.payload as Succeeded
    }
    logVerbose { startPluginResult.toString() }
  }

  fun handle(): Int {
    logDebug { "reader/writer constructed" }

    // Since we're a client, we will write to stdin (and read from stdout/stderr)
    writer.openStdinForWriting()
    val verifyProtocolVersionRequestId = writer.writeRequest(
      VerifyProtocolVersion(STOIC_PROTOCOL_VERSION)
    )

    val startPluginRequestId = writer.writeRequest(
      StartPlugin(
        pluginName = pluginName,
        pluginSha = pluginDexJarSha256Sum,
        pluginArgs = pluginParsedArgs.pluginArgs,
        minLogLevel = minLogLevel.name,
        env = pluginParsedArgs.pluginEnvVars,
      )
    )

    // To minimize roundtrips, we don't read the version result until after we've written RunPlugin
    handleVersionResult(verifyProtocolVersionRequestId)
    handleStartPluginResult(startPluginRequestId)

    val isFinished = AtomicBoolean(false)
    thread(name = "stdin-daemon", isDaemon = true) {
      val buffer = ByteArray(8192)
      try {
        while (true) {
          if (isFinished.get()) {
            break
          }

          val byteCount = System.`in`.read(buffer, 0, buffer.size)
          if (byteCount == -1) {
            writer.writeOneWay(ByteArray(0), STDIN, true)
            break
          } else {
            check(byteCount > 0)
            val bytes = buffer.copyOfRange(0, byteCount)
            writer.writeOneWay(bytes, STDIN, false)
          }
        }
      } catch (e: Throwable) {
        if (e is IOException) {
          // this is unconcerning - the connection is being torn down as we attempt to pump stdin
          logVerbose { "exception while pumping stdin (unconcerning) ${e.stackTraceToString()}"}
        } else {
          logError { e.stackTraceToString() }
          throw e
        }
      }
    }

    while (true) {
      val requestId: Int
      val payload = reader.consumeNext().let {
        requestId = it.requestId
        it.payload
      }
      when (payload) {
        is ByteArray -> {
          when (requestId) {
            STDOUT -> System.out.write(payload)
            STDERR -> System.err.write(payload)
            else -> throw IllegalArgumentException("Unrecognized stream id: ${requestId}")
          }
        }
        is PluginFinished -> {
          // To allow the server to stop pumping stdin cleanly, we write a StreamClosed message.
          writer.writeOneWay(ByteArray(0), STDIN, isComplete = true)
          isFinished.set(true)
          return payload.exitCode
        }
        else -> throw IllegalArgumentException("Unexpected msg: $payload")
      }
    }
  }
}
