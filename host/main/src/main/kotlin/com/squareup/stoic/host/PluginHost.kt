package com.squareup.stoic.host

import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.Failed
import com.squareup.stoic.common.FailureCode
import com.squareup.stoic.common.LoadPlugin
import com.squareup.stoic.common.MessageReader
import com.squareup.stoic.common.MessageWriter
import com.squareup.stoic.common.PluginFinished
import com.squareup.stoic.common.STDIN
import com.squareup.stoic.common.STDOUT
import com.squareup.stoic.common.STDERR
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.StartPlugin
import com.squareup.stoic.common.Succeeded
import com.squareup.stoic.common.VerifyProtocolVersion
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logVerbose
import com.squareup.stoic.common.minLogLevel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

const val stoicDeviceDir = "/data/local/tmp/.stoic"
const val stoicDeviceSyncDir = "$stoicDeviceDir/sync"

class PluginHost(
  apkInfo: FileWithSha?,
  val pluginParsedArgs: PluginParsedArgs,
  inputStream: InputStream,
  outputStream: OutputStream
) {
  val pluginApk = apkInfo?.file
  val pluginApkSha256Sum = apkInfo?.sha256sum
  val pluginName = pluginApk?.name?.removeSuffix(".apk") ?: pluginParsedArgs.pluginModule
  val writer = MessageWriter(DataOutputStream(outputStream))
  val reader = MessageReader(DataInputStream(inputStream))

  fun handleVersionResult(verifyProtocolVersionRequestId: Int) {
    try {
      val succeeded = reader.consumeNext().let {
        check(it.isResponse && it.isComplete)
        check(it.requestId == verifyProtocolVersionRequestId)
        it.payload as Succeeded
      }
      logDebug { succeeded.message }
    } catch (e: EOFException) {
      // This means the server isn't up yet
      throw e
    } catch (e: Exception) {
      logDebug { "Failed to handle version result: ${e.stackTraceToString()}" }

      // We treat any other exception encountered while handling the verify-protocol-version request
      // as a MismatchedVersionException because it's likely that a change in the structure of the
      // request/response is responsible for the problem.
      throw MismatchedVersionException(e.message)
    }
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
    if (pluginApk == null) {
      // TODO: Need to throw a sub-class of PithyException so we can catch it and try a tool instead
      throw PithyException(
        """
          Plugin not found: ${pluginParsedArgs.pluginModule}
          To list available plugins: stoic tool list
        """.trimIndent()
      )
    }

    val loadPluginRequestId = writer.writeRequest(
        LoadPlugin(
        pluginName = pluginName,
        pluginSha = pluginApkSha256Sum!!,
      ),
      isComplete = false
    )
    writer.writeRequest(pluginApk.readBytes(), requestId = loadPluginRequestId)
    val startPluginRequestId = writer.writeRequest(
      StartPlugin(
        pluginName = pluginName,
        pluginSha = pluginApkSha256Sum,
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

    // Since we're the host, we will write to stdin (and read from stdout/stderr)
    writer.openStdinForWriting()
    val verifyProtocolVersionRequestId = writer.writeRequest(
      VerifyProtocolVersion(STOIC_PROTOCOL_VERSION, StoicProperties.STOIC_VERSION_NAME)
    )

    val startPluginRequestId = writer.writeRequest(
      StartPlugin(
        pluginName = pluginName,
        pluginSha = pluginApkSha256Sum,
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
