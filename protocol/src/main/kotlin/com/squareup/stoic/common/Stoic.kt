package com.squareup.stoic.common

private const val MAX_STATUS_LENGTH = 80
private val CLEAR_STATUS = " ".repeat(MAX_STATUS_LENGTH) + "\r"
var showStatus: Boolean = true
var statusShowing: Boolean = false
var minLogLevel: LogLevel = LogLevel.DEBUG

enum class LogLevel(val level: Int) {
  VERBOSE(2),
  DEBUG(3),
  INFO(4),
  WARN(5),
  ERROR(6),
  ASSERT(7);

  fun meetsMinimumLevel(minimumLevel: LogLevel): Boolean {
    return this.level >= minimumLevel.level
  }

  fun isLoggable(): Boolean {
    return this >= minLogLevel
  }
}

fun log(level: LogLevel, msg: () -> String) {
  if (level >= minLogLevel) {
    var realizedMsg = msg()
    if (statusShowing) {
      // We need to clear the status first
      realizedMsg = CLEAR_STATUS + realizedMsg
      statusShowing = false
    }
    System.err.println(realizedMsg)
  }
}

fun status(msg: String) {
  if (showStatus) {
    var msgToDisplay = msg.take(MAX_STATUS_LENGTH) + "\r"
    if (statusShowing) {
      msgToDisplay = CLEAR_STATUS + msgToDisplay
    }

    System.err.print(msgToDisplay)
    statusShowing = true
  }
}

fun clearStatus() {
  if (statusShowing) {
    System.err.print(CLEAR_STATUS)
    statusShowing = false
  }
}

inline fun <T> withStatus(msg: String, block: () -> T): T {
  status(msg)
  return try {
    block()
  } finally {
    clearStatus()
  }
}

fun logVerbose(msg: () -> String) = log(LogLevel.VERBOSE, msg)

fun logDebug(msg: () -> String) = log(LogLevel.DEBUG, msg)

fun logInfo(msg: () -> String) = log(LogLevel.INFO, msg)

fun logWarn(msg: () -> String) = log(LogLevel.WARN, msg)

fun logError(msg: () -> String) = log(LogLevel.ERROR, msg)

inline fun <T> logBlock(level: LogLevel, msg: () -> String, block: () -> T): T {
  val msgValue =
    if (level >= minLogLevel) {
      msg()
    } else {
      ""
    }

  log(level) { "Starting $msgValue..." }
  return runCatching { block() }
    .onSuccess { log(level) { "Finished $msgValue." } }
    .onFailure { e ->
      log(level) { "Aborted $msgValue (--verbose to see Throwable)" }
      log(LogLevel.VERBOSE) { e.stackTraceToString() }
    }
    .getOrThrow()
}

// Utility for debug output
fun Any?.toKotlinRepr(): String =
  when (this) {
    null -> "null"
    is CharSequence -> {
      val unquoted =
        this.toString()
          .replace("\\", "\\\\")
          .replace("\n", "\\n")
          .replace("\t", "\\t")
          .replace("\b", "\\b")
          .replace("\r", "\\r")
          .replace("\"", "\\\"")
          .replace("\$", "\\\$")
      "\"" + unquoted + "\""
    }
    is List<*> -> this.toKotlinListRepr()
    else -> this.toString()
  }

fun List<*>.toKotlinListRepr(): String {
  val reprElements = this.joinToString(separator = ", ") { it.toKotlinRepr() }
  return "listOf($reprElements)"
}

// Socket communication
const val SOCKET_PREFIX = "/stoic"
const val SERVER_SUFFIX = "server"

fun serverSocketName(pkg: String): String {
  return "$SOCKET_PREFIX/$pkg/$SERVER_SUFFIX"
}
