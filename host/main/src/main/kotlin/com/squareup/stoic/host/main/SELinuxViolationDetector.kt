package com.squareup.stoic.host.main

import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.host.resolvedProcessBuilder
import com.squareup.stoic.host.runCommand
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections
import kotlin.concurrent.thread

// TODO: port this code to work on host, or in stoic-attach
class SELinuxViolationDetector {
  private val selinuxViolations = Collections.synchronizedList(mutableListOf<String>())
  private var logcatProcess: Process? = null
  fun start(pkg: String) {
    logcatProcess = ProcessBuilder(listOf("logcat", "-T", "1", "*:w")).start()

    val logcatReader = logcatProcess!!.inputStream.bufferedReader(UTF_8)
    thread {
      while (true) {
        val line = try {
          logcatReader.readLine()
        } catch (e: IOException) {
          // Probably we killed the logcat process
          break
        }

        if (line == null) {
          // Usually this means that we hit the timeout and we killed the logcat process
          // In rare circumstances it might mean that the adb connection died
          logDebug { "logcat end-of-stream" }
          break
        }

        if ("^.*avc: denied.*permissive=0.*app=${pkg.replace(".", "\\.")}\$".toRegex().matches(line)) {
          logDebug { line }
          selinuxViolations.add(line)
        }
      }
    }
  }

  fun foundViolation(): Boolean {
    return selinuxViolations.isNotEmpty()
  }

  fun stop() {
    logcatProcess?.destroyForcibly()
}

  fun showViolations() {
    // https://wiki.sqprod.co/display/TREX/How+to%3A+Fix+SELinux+Errors
    if (selinuxViolations.isEmpty()) {
      return
    }

    logError { "Detected SELinux violations!" }
    logError { "--------" }
    for (violation in selinuxViolations) {
      logError { violation }
    }
    logError { "--------\n" }

    if (runCommand(listOf("which", "su")).isNotBlank()) {
      logError { "Recommended fix (may need to tweak command depending on version of su):" }
      logError { "\n    adb shell su 0 setenforce 0\n" }
    } else {
      // Note: It could be the SELinux violations are unrelated to stoic
      // TODO: better diagnostics of what exactly SELinux is disallowing
      logError { "Your device's SELinux configuration may be incompatible with JVMTI." }
    }

  }


}
