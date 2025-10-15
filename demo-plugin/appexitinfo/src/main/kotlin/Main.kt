import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.StrictMode
import com.squareup.stoic.threadlocals.stoic
import com.squareup.stoic.helpers.*
import com.squareup.stoic.jvmti.JvmtiClass
import com.squareup.stoic.jvmti.magics.*
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets.UTF_8
import java.text.SimpleDateFormat
import java.util.Date

fun main(args: Array<String>) {
  StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).permitNonSdkApiUsage().build())
  var errId: String? = null
  if (args.isNotEmpty()) {
    assert(args[0] == "--id")
    errId = args[1]
  }

  eprint("Searching heap for instances of android.app.Application")
  val apps = stoic.jvmti.instances(Application::class.java)
  eprint("\r\u001B[K")

  for (app in apps) {
    val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Note: This API requires a manifest-declared permission.
    // TODO: Sensible error message when this isn't present (or when we're on Android 10 or below)
    val exits = activityManager.getHistoricalProcessExitReasons(null, 0, 0)
    if (errId == null) {
      printTabular(exits)
    } else {
      val epoch = decodeIdToEpoch(errId)
      val matchedExits = exits.filter { it.timestamp == epoch }
      if (matchedExits.isEmpty()) {
        eprintln("No exit found matching $errId")
      } else {
        for (exit in matchedExits) {
          println(formatDetailedAppExitInfo(exit))
        }
      }
    }
  }
}

fun printTabular(exits: List<ApplicationExitInfo>) {
  val headers = listOf("id", "timestamp", "process-name", "reason", "sub-reason", "importance")
  val rows = mutableListOf<List<String>>()
  for (exit in exits) {
    val reasonString = getConstantNameByValue(
      ApplicationExitInfo::class.java,
      "REASON_",
      exit.reason
    )

    val importanceString = getConstantNameByValue(
      ActivityManager.RunningAppProcessInfo::class.java,
      "IMPORTANCE_",
      exit.importance
    )

    val subReasonString = try {
      getConstantNameByValue(
        ApplicationExitInfo::class.java,
        "SUBREASON_",
        exit.m["getSubReason"]() as Int
      )
    } catch (e: ReflectiveOperationException) {
      "<unavailable>"
    }

    val epoch = exit.timestamp
    val errId = encodeEpochAsId(epoch)
    val processName = exit.processName
    val timeString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(epoch))
    rows.add(listOf(errId, timeString, processName, reasonString, subReasonString, importanceString))
  }

  val columnWidths = headers.map { it.length }.toMutableList()

  for (row in rows) {
    row.forEachIndexed { index, value ->
      if (value.length > columnWidths[index]) {
        columnWidths[index] = value.length
      }
    }
  }

  val separatorLine = columnWidths.joinToString(separator = "   ") { "-".repeat(it) }
  val formatString = columnWidths.joinToString(separator = "   ") { "%-${it}s" }

  println(formatString.format(*headers.toTypedArray()))
  println(separatorLine)

  for (row in rows) {
    println(formatString.format(*row.toTypedArray()))
  }
}

// These characters are easily distinguishable, and they don't count as separators in iTerm, so you
// can double-click to copy/paste
const val codecAlphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz~_-+./"
fun encodeEpochAsId(epoch: Long): String {
  assert(codecAlphabet.length == 64)  // 6 bits
  val sb = StringBuilder()
  var remaining = epoch
  while (remaining > 0) {
    // Get 6 bits
    val current = (remaining and 0x3f).toInt()
    sb.append(codecAlphabet[current])
    remaining = (remaining shr 6)
  }

  val id = sb.toString().reversed()
  //eprintln("$epoch -> $id")
  return id
}

fun decodeIdToEpoch(id: String): Long {
  assert(codecAlphabet.length == 64)  // 6 bits
  var epoch: Long = 0
  for (c in id) {
    val index = codecAlphabet.indexOf(c).toLong()
    epoch = (epoch shl 6) or index
  }

  //eprintln("Decoded $id -> $epoch")
  return epoch
}

fun getConstantNameByValue(constantsClass: Class<*>, fieldPrefix: String, value: Int, includePrefix: Boolean = false): String {
  val valueToNameMap = JvmtiClass[constantsClass].declaredFields
    .filter { it.signature == "I" }
    .filter { it.modifiers and Modifier.STATIC != 0}
    .filter { it.modifiers and Modifier.FINAL != 0}
    .filter { it.name.startsWith(fieldPrefix) }
    .associate { it.get(null) to it.name }

  val result = valueToNameMap[value] ?: return "Unknown constant value: $value"
  return if (includePrefix) { result } else { result.drop(fieldPrefix.length) }
}

fun formatDetailedAppExitInfo(exit: ApplicationExitInfo): String {
  val reasonString = getConstantNameByValue(
    ApplicationExitInfo::class.java,
    "REASON_",
    exit.reason)

  val subReasonString = try {
    getConstantNameByValue(
      ApplicationExitInfo::class.java,
      "SUBREASON_",
      exit.m["getSubReason"]() as Int
    )
  } catch (e: ReflectiveOperationException) {
    "<unavailable>"
  }

  val importanceString = getConstantNameByValue(
    ActivityManager.RunningAppProcessInfo::class.java,
    "IMPORTANCE_",
    exit.importance)

  val epoch = exit.timestamp
  val processName = exit.processName
  val timeString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(epoch))

  val packageList = try {
    exit.m["getPackageList"]()
  } catch (e: ReflectiveOperationException) {
    arrayOf("<unavailable>")
  }

  val packageListString = if (packageList == null) {
    "null"
  } else {
    (packageList as Array<*>).joinToString(" ")
  }

  // TODO: Encode this to base64
  val processStateSummary = exit.processStateSummary

  val detailed = """
    Process Name: $processName
    Timestamp: $timeString (epoch: $epoch)
    Reason: $reasonString
    Sub-reason: $subReasonString
    Importance: $importanceString
    Description: ${exit.description}
    Status: ${exit.status}
    Pss: ${exit.pss}
    Rss: ${exit.rss}
    Pid: ${exit.pid}
    Real Uid: ${exit.realUid}
    Package Uid: ${exit.packageUid}
    Defining Uid: ${exit.definingUid}
    Package List: $packageListString
    Process State Summary: $processStateSummary
  """.trimIndent()

  val trace = when (exit.reason) {
    ApplicationExitInfo.REASON_ANR -> {
      exit.traceInputStream?.bufferedReader(UTF_8).use { it?.readText()?.trim() }
    }
    ApplicationExitInfo.REASON_CRASH_NATIVE -> {
      "TODO: Use https://android.googlesource.com/platform/system/core/+/refs/heads/master/debuggerd/proto/tombstone.proto to parse com.squareup.stoic.trace.trace-input-stream"
    }
    else -> ""
  }

  return "$detailed\n\n$trace"
}
