package com.squareup.stoic.host

import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.toKotlinRepr
import java.io.File
import java.lang.ProcessBuilder.Redirect

// Host-specific command execution utilities
val globalEnvOverrides = mutableMapOf<String, String>()

fun runCommand(
  commandParts: List<String>,
  inheritIO: Boolean = false,
  shell: Boolean = false,
  directory: String? = null,
  chomp: Boolean = true,
  envOverrides: Map<String, String>? = null,
  expectedExitCode: Int = 0,
  redirectErrorStream: Boolean = false,
  redirectError: Redirect? = null,
  redirectOutputAndError: Redirect? = null,
  redirectOutput: Redirect? = null,
): String =
  logBlock(
    LogLevel.DEBUG,
    {
      "runCommand(${commandParts.toKotlinRepr()}, $inheritIO, $shell, $directory, $chomp, $envOverrides, $expectedExitCode)"
    },
  ) {
    val builder =
      resolvedProcessBuilder(
        commandParts,
        shell = shell,
        directory = directory,
        envOverrides = envOverrides,
        redirectErrorStream = redirectErrorStream,
        redirectError = redirectError,
        redirectOutput = redirectOutput,
        redirectOutputAndError = redirectOutputAndError,
      )

    if (inheritIO) {
      builder.inheritIO()
    }

    val process = builder.start().also { it.waitFor() }

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != expectedExitCode) {
      val errorOutput = process.errorStream.bufferedReader().readText()
      throw FailedExecException(
        exitCode,
        "Failed command: '${commandParts.toKotlinRepr()}', exitCode=$exitCode, error output:\n---\n$errorOutput---",
        errorOutput,
      )
    }

    if (chomp) {
      output.removeSuffix("\n")
    } else {
      output
    }
  }

fun resolvedProcessBuilder(
  command: List<String>,
  shell: Boolean = false,
  directory: String? = null,
  envOverrides: Map<String, String>? = null,
  redirectErrorStream: Boolean = false,
  redirectError: Redirect? = null,
  redirectOutput: Redirect? = null,
  redirectOutputAndError: Redirect? = null,
): ProcessBuilder {
  val resolvedCommand =
    if (shell) {
      listOf("sh", "-c") + command
    } else {
      listOf("sh", "-c", "\"\$0\" \"\$@\"") + command
    }

  val builder = ProcessBuilder(resolvedCommand).redirectErrorStream(redirectErrorStream)
  if (redirectOutputAndError != null) {
    builder.redirectOutput(redirectOutputAndError).redirectError(redirectOutputAndError)
  } else if (redirectError != null) {
    builder.redirectError(redirectError)
  } else {
    builder.redirectError(Redirect.INHERIT)
  }
  if (redirectOutputAndError == null && redirectOutput != null) {
    builder.redirectOutput(redirectOutput)
  }

  val env = builder.environment()
  env.putAll(globalEnvOverrides)
  envOverrides?.let { env.putAll(envOverrides) }

  if (directory != null) {
    builder.directory(File(directory))
  }

  return builder
}

fun ProcessBuilder.waitFor(expectedExitCode: Int? = 0): Int {
  return start().waitFor(expectedExitCode, command())
}

fun Process.waitFor(expectedExitCode: Int?, command: List<String>? = null): Int {
  val exitCode = waitFor()

  if (expectedExitCode != null && expectedExitCode != exitCode) {
    val errorOutput =
      errorStream?.reader()?.readText()?.let {
        if (it.isBlank()) {
          null
        } else {
          it.removeSuffix("\n")
        }
      }
    val errorOutputMsg =
      if (errorOutput != null) {
        ", error output:\n---\n$errorOutput\n---"
      } else {
        ""
      }
    val commandString =
      if (command != null) {
        "'${command.toKotlinRepr()}', "
      } else {
        ""
      }
    throw FailedExecException(
      exitCode,
      "Failed command: ${commandString}exitCode=$exitCode$errorOutputMsg",
      errorOutput,
    )
  }
  return exitCode
}

fun ProcessBuilder.stdout(expectedExitCode: Int? = 0, chomp: Boolean = true): String {
  redirectOutput(Redirect.PIPE)
  val process = start()
  process.waitFor(expectedExitCode, command())

  val output = process.inputStream.bufferedReader().readText()

  return if (chomp) {
    output.removeSuffix("\n")
  } else {
    output
  }
}
