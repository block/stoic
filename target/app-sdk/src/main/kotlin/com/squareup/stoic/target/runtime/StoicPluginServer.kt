package com.squareup.stoic.target.runtime

import android.util.Log
import com.squareup.stoic.ExitCodeException
import com.squareup.stoic.Stoic
import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.Failed
import com.squareup.stoic.common.FailureCode
import com.squareup.stoic.common.JvmtiAttachOptions
import com.squareup.stoic.common.LoadPlugin
import com.squareup.stoic.common.Succeeded
import com.squareup.stoic.common.MessageReader
import com.squareup.stoic.common.MessageWriter
import com.squareup.stoic.common.PluginFinished
import com.squareup.stoic.common.ProtocolError
import com.squareup.stoic.common.STDERR
import com.squareup.stoic.common.StartPlugin
import com.squareup.stoic.common.STDIN
import com.squareup.stoic.common.STDOUT
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.VerifyProtocolVersion
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.common.logVerbose
import com.squareup.stoic.plugin.StoicPlugin
import com.squareup.stoic.threadlocals.stoic
import dalvik.system.DexClassLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import kotlin.Exception
import kotlin.concurrent.thread

class StoicPluginServer(
  private val stoicDir: String,
  private val options: JvmtiAttachOptions,
  extraPlugins: Map<String, StoicPlugin>,
  private val socketInputStream: InputStream,
  private val socketOutputStream: OutputStream,
) {
  private val writer = MessageWriter(DataOutputStream(socketOutputStream))
  private val reader = MessageReader(DataInputStream(socketInputStream))
  private val embeddedPlugins: Map<String, StoicPlugin> = run {
    val defaultPlugins = mapOf(
      "stoic-status" to object : StoicPlugin {
        override fun run(args: List<String>): Int {
          stoic.stdout.println(
            """
              protocol-version: ${options.stoicProtocolVersion}
              attached-via: ${options.attachedVia}
              embedded-plugins: ${embeddedPlugins.keys}
            """.trimIndent()
          )

          return 0
        }
      },
      "stoic-list" to object : StoicPlugin {
        override fun run(args: List<String>): Int {
          embeddedPlugins.keys.forEach { stoic.stdout.println(it) }

          return 0
        }
      },
      "stoic-noop" to object : StoicPlugin {
        override fun run(args: List<String>): Int {
          // This is used to ensure the server is running
          return 0
        }
      },
    )
    defaultPlugins + extraPlugins
  }

  init {
    logVerbose { "constructed writer from ${writer.dataOutputStream} (underlying ${socketOutputStream})" }
    logVerbose { "constructed reader from ${reader.dataInputStream} (underlying ${socketInputStream})" }
  }


  fun handleVersion() {
    val decodedMessage = reader.consumeNext()
    check(decodedMessage.isRequest)
    val payload = decodedMessage.payload as VerifyProtocolVersion
    if (payload.protocolVersion != STOIC_PROTOCOL_VERSION) {
      writer.writeResponse(
        decodedMessage.requestId,
        Failed(
          FailureCode.UNSPECIFIED.value,
          "Version mismatch - expected $STOIC_PROTOCOL_VERSION, received ${payload.protocolVersion}"
        )
      )
      throw FailedOperationException()
    } else {
      val msg = if (payload.stoicVersionName != StoicProperties.STOIC_VERSION_NAME) {
        // This is normally fine, but if we forget to rev the protocol version when we make a
        // breaking change it will cause problems. If we see a problem it's worth considering if
        // there was a breaking change.
        "version check succeeded but stoic version name doesn't match " +
          "${payload.stoicVersionName} != ${StoicProperties.STOIC_VERSION_NAME}"
      } else {
        "version check succeeded and stoic version name matches: ${StoicProperties.STOIC_VERSION_NAME}"
      }
      logInfo { msg }
      writer.writeResponse(decodedMessage.requestId, Succeeded(msg))
    }
  }

  fun handlePlugin() {
    while (true) {
      when (reader.peekNext().payload) {
        is StartPlugin -> {
          // Once handleStartPlugin succeeds we're done.
          if (handleStartPlugin()) {
            return
          } else {
            continue
          }
        }
        is LoadPlugin -> handleLoadPlugin()
        else -> {
          logVerbose { "Exiting handlePlugin - next message is ${reader.peekNext()}" }
          return
        }
      }
    }
  }

  /**
   * Returns true for success, false for failure
   */
  fun handleStartPlugin(): Boolean {
    val startPluginRequestId: Int
    val startPlugin: StartPlugin

    run {
      val decodedMessage = reader.consumeNext()
      check(decodedMessage.isRequest)
      startPlugin = decodedMessage.payload as StartPlugin
      startPluginRequestId = decodedMessage.requestId
    }

    val oldClassLoader = Thread.currentThread().contextClassLoader
    try {
      val plugin = if (startPlugin.pluginSha != null) {
        val parentClassLoader = StoicPluginServer::class.java.classLoader
        val pluginDir = "$stoicDir/plugin-by-sha/${startPlugin.pluginSha}"
        val pluginApk = File("$pluginDir/${startPlugin.pluginName}.apk")
        if (!pluginApk.exists()) {
          writer.writeResponse(
            startPluginRequestId,
            Failed(
              FailureCode.PLUGIN_MISSING.value,
              "$pluginApk not loaded"
            )
          )
          return false
        }

        val dexoutDir = File("$pluginDir/${startPlugin.pluginName}-dexout")
        dexoutDir.mkdirs()
        Log.d("stoic", "Making classLoader: (pluginApk: $pluginApk, dexoutDir: $dexoutDir)")

        // It's important to use canonical paths to avoid triggering
        // https://github.com/square/stoic/issues/2
        val classLoader = DexClassLoader(
          pluginApk.canonicalPath,
          dexoutDir.canonicalPath,
          null,
          parentClassLoader)

        Log.d("stoic", "made classLoader: $classLoader (parent: $parentClassLoader)")

        // TODO: It'd be nice to allow people to follow the Java convention of declaring a main
        //   method in the manifest, and then insert a StoicMainKt wrapper in the apk (or even
        //   insert the manifest into the apk)
        val pluginMainClass = classLoader.loadClass("MainKt")
        Log.d("stoic", "loaded class: $pluginMainClass via ${pluginMainClass.classLoader}")
        val args = startPlugin.pluginArgs.toTypedArray()
        val pluginMain = pluginMainClass.getDeclaredMethod("main", args.javaClass)

        // Set the context classloader so plugin code can load classes from its own JAR
        Thread.currentThread().contextClassLoader = classLoader

        object: StoicPlugin {
          override fun run(args: List<String>): Int {
            return try {
              pluginMain.invoke(null, args.toTypedArray())
              0
            } catch (e: InvocationTargetException) {
              Log.e("stoic", "plugin crashed", e)
              val targetException = e.targetException
              if (targetException !is ExitCodeException) {
                targetException.printStackTrace(stoic.stderr)
                1
              } else {
                targetException.code
              }
            } catch (e: ReflectiveOperationException) {
              Log.e("stoic", "problem starting plugin", e)
              e.printStackTrace(stoic.stderr)
              1
            }
          }
        }
      } else if (startPlugin.pluginName != null) {
        val p = embeddedPlugins[startPlugin.pluginName]
        if (p == null) {
          val msg = "No embedded plugin named: ${startPlugin.pluginName}"
          Log.i("stoic", msg)
          writer.writeResponse(
            startPluginRequestId,
            Failed(FailureCode.PLUGIN_MISSING.value, msg)
          )
          return false
        } else {
          p
        }
      } else {
        throw IllegalArgumentException(
          "At least one of pluginName/pluginSha must be specified: $startPlugin"
        )
      }

      // Plugin may write to stdout/stderr
      writer.openStdoutForWriting()
      writer.openStderrForWriting()

      val stdinOutPipe = PipedOutputStream()
      val stdin = PipedInputStream(stdinOutPipe)
      val stdout = PrintStream(MessageWriterOutputStream(STDOUT, writer))
      val stderr = PrintStream(MessageWriterOutputStream(STDERR, writer))
      val pluginStoic = Stoic(startPlugin.env, stdin, stdout, stderr)

      writer.writeResponse(startPluginRequestId, Succeeded("Plugin started"))

      var exitCode = -1
      val t = thread {
        exitCode = pluginStoic.callWith {
          plugin.run(startPlugin.pluginArgs)
        }

        // We write PluginFinished to signal the client to send StreamClosed(STDIN), which signals us
        // to stop pumping messages
        writer.writeOneWay(PluginFinished(exitCode))
      }

      while (true) {
        val decodedMessage = reader.consumeNext()
        val payload = decodedMessage.payload
        val requestId = decodedMessage.requestId
        when (payload) {
          is ByteArray -> {
            if (requestId != STDIN) { throw IllegalArgumentException("Unexpected stream id: $requestId") }
            stdinOutPipe.write(payload)
            if (decodedMessage.isComplete) {
              logVerbose { "StreamClosed(STDIN)" }
              stdinOutPipe.close()

              // TODO: Really, we shouldn't break here - there might be other inputs to pump
              //   But, right now we don't surface those to clients, so it's okay.
              //   If we didn't break here, we'd need an alternate way to end the pump when the plugin
              //   finished.
              break
            }
          }
        }
      }
      t.join()
    } finally {
      Thread.currentThread().contextClassLoader = oldClassLoader
    }

    return true
  }

  fun handleLoadPlugin() {
    val requestId: Int
    val loadPlugin = reader.consumeNext().let {
      check(it.isRequest)
      check(!it.isComplete)
      requestId = it.requestId
      it.payload as LoadPlugin
    }
    val loadPluginBytes = reader.consumeNext().let {
      check(it.isRequest)

      // TODO: support streaming the plugin across multiple StreamIO messages
      check(it.isComplete)

      it.payload as ByteArray
    }

    val pluginByShaDir = File("$stoicDir/plugin-by-sha/${loadPlugin.pluginSha}")
    if (pluginByShaDir.exists()) {
      // We are reloading the directory - need to clear it first
      pluginByShaDir.deleteRecursively()
    }

    pluginByShaDir.mkdirs()
    val pluginApk = File(pluginByShaDir, "${loadPlugin.pluginName}.apk")
    pluginApk.writeBytes(loadPluginBytes)
    pluginApk.setWritable(false)
    writer.writeResponse(requestId, Succeeded("Load plugin succeeded"))
  }

  fun pluginMain() {
    Log.i("stoic", "pluginMain")
    try {
      handleVersion()
      handlePlugin()
    } catch (e: Throwable) {
      Log.e("stoic", "pluginMain threw", e)
      // TODO: we should also write to stderr
      writer.writeOneWay(ProtocolError(e.stackTraceToString()))

      // TODO: Instead of this hacky sleep, we should wait for an ACK from the client
      // Give the message time to make it to the other side before we close the connection
      Thread.sleep(1000)
    }
  }
}

class FailedOperationException : Exception()
