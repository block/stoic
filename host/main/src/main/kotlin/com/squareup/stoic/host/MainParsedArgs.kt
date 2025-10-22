package com.squareup.stoic.host

import com.squareup.stoic.common.LogLevel.DEBUG
import com.squareup.stoic.common.LogLevel.ERROR
import com.squareup.stoic.common.LogLevel.INFO
import com.squareup.stoic.common.LogLevel.VERBOSE
import com.squareup.stoic.common.LogLevel.WARN
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.minLogLevel

class MainParsedArgs(
  val stoicArgs: List<String>,
  val command: String,
  val commandArgs: List<String>,
) {
  companion object {
    fun parse(args: Array<String>): MainParsedArgs {
      val stoicArgs = mutableListOf<String>()
      var command: String? = null

      var i = -1
      while (++i < args.size) {
        val arg = args[i]
        if (arg.startsWith("-")) {
          when (arg) {
            "--verbose" -> minLogLevel = VERBOSE
            "--debug" -> minLogLevel = DEBUG
            "--info" -> minLogLevel = INFO
            "--warn" -> minLogLevel = WARN
            "--error" -> minLogLevel = ERROR
            "--log" -> {
              val level = args[++i]
              minLogLevel =
                when {
                  level.toInt() == VERBOSE.level || level.equals("verbose", ignoreCase = true) ->
                    VERBOSE
                  level.toInt() == DEBUG.level || level.equals("debug", ignoreCase = true) -> DEBUG
                  level.toInt() == INFO.level || level.equals("info", ignoreCase = true) -> INFO
                  level.toInt() == WARN.level || level.equals("warn", ignoreCase = true) -> WARN
                  level.toInt() == ERROR.level || level.equals("error", ignoreCase = true) -> ERROR
                  else -> throw IllegalArgumentException("Unrecognized log level: $level")
                }
            }
            "--package",
            "--pkg" -> {
              // option with arg
              stoicArgs.add(arg)
              stoicArgs.add(args[++i])
            }
            else -> stoicArgs.add(arg)
          }
        } else {
          command = args[i]
          break
        }
      }

      if (command == null) {
        throw PithyException("Missing command: ${args.toList()}")
      }

      val commandArgs = args.slice(i + 1..<args.size)
      logDebug { "args: ${args.toList()}" }
      logDebug { "stoicArgs: $stoicArgs" }
      logDebug { "command: $command" }
      logDebug { "commandArgs: $commandArgs" }

      return MainParsedArgs(stoicArgs, command, commandArgs)
    }
  }
}
