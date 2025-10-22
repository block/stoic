import EvalTime.ON_ENTRY
import EvalTime.ON_EXIT
import com.squareup.stoic.Stack
import com.squareup.stoic.helpers.eprintln
import com.squareup.stoic.helpers.exit
import com.squareup.stoic.helpers.println
import com.squareup.stoic.jvmti.JvmtiMethod
import com.squareup.stoic.jvmti.LocalVariable
import com.squareup.stoic.threadlocals.jvmti
import java.util.concurrent.CountDownLatch

fun usage() {
  eprintln("Usage: stoic breakpoint [options] signature1 [options] signature2 ...")
  eprintln("Each signature should adhere to the JVM signature spec.")
  eprintln("e.g. 'java/util/Map.get(Ljava/util/Object;)Ljava/util/Object;'")
  eprintln("you will probably need to quote so that your shell doesn't interpret treat the")
  eprintln("';' / '(' / ')' chars specially.")
  exit(1)
}

enum class EvalTime {
  DEFAULT,
  ON_ENTRY,
  ON_EXIT,
}

enum class PrintType {
  DEFAULT,
  TO_STRING,
  IDENTITY_HASH,
}

data class PrintDesc(
  val expr: String,
  val evalTime: EvalTime = EvalTime.DEFAULT,
  val printType: PrintType = PrintType.DEFAULT,
)

data class PrintSpec(
  // null means return value
  val localVariable: LocalVariable<*>?,
  val evalTime: EvalTime,
  val printType: PrintType,
)

data class BpDesc(val sig: String, val dumpStack: Boolean, val printDescs: List<PrintDesc>)

data class BpSpec(
  val method: JvmtiMethod,
  val dumpStack: Boolean,
  val hasOnExit: Boolean,
  val printSpecs: List<PrintSpec>,
)

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    usage()
  }

  var i = -1
  val bpDescs = mutableListOf<BpDesc>()
  val printDescs = mutableListOf<PrintDesc>()
  var dumpStack = false
  while (++i < args.size) {
    val arg = args[i]
    if (!arg.startsWith("-")) {
      bpDescs.add(BpDesc(arg, dumpStack = dumpStack, printDescs = printDescs.toList()))
      dumpStack = false
      printDescs.clear()
      continue
    }

    when (arg) {
      "--stack",
      "-s" -> dumpStack = true
      "-p" -> {
        val expr = args[++i]
        printDescs.add(PrintDesc(expr))
      }
      else -> {
        eprintln("Unrecognized option: $arg")
        exit(1)
      }
    }
  }

  val bpSpecs =
    bpDescs.map { bpDesc ->
      val method = JvmtiMethod.bySig(bpDesc.sig)
      val printSpecs =
        bpDesc.printDescs.flatMap { printDesc ->
          when (printDesc.expr) {
            "*" -> {
              val evalTime =
                if (printDesc.evalTime == EvalTime.DEFAULT) {
                  ON_ENTRY
                } else {
                  printDesc.evalTime
                }
              method.arguments.map { PrintSpec(it, evalTime, printDesc.printType) }
            }
            "return" -> {
              check(printDesc.evalTime != ON_ENTRY)
              listOf(PrintSpec(null, ON_EXIT, printDesc.printType))
            }
            else -> {
              val evalTime =
                if (printDesc.evalTime == EvalTime.DEFAULT) {
                  ON_ENTRY
                } else {
                  printDesc.evalTime
                }
              listOf(
                PrintSpec(method.argumentByName<Any>(printDesc.expr), evalTime, printDesc.printType)
              )
            }
          }
        }
      val hasOnExit = printSpecs.any { it.evalTime == ON_EXIT }
      BpSpec(method, bpDesc.dumpStack, hasOnExit, printSpecs)
    }

  for (bpSpec in bpSpecs) {
    jvmti.breakpoint(bpSpec.method.startLocation) { entryFrame ->
      val values = mutableListOf<String>()
      for (printSpec in bpSpec.printSpecs.filter { it.evalTime == ON_ENTRY }) {
        val str = entryFrame.get(printSpec.localVariable!!).toString()
        values.add(str)
      }

      if (bpSpec.hasOnExit) {
        // We need to wait until exit to print any values

        entryFrame.onExit { exitFrame, returnValue, wasPoppedByException ->
          for (printSpec in bpSpec.printSpecs.filter { it.evalTime == ON_EXIT }) {
            val str =
              if (printSpec.localVariable != null) {
                exitFrame.get(printSpec.localVariable).toString()
              } else {
                returnValue?.toString() ?: "null"
              }
            values.add(str)
          }
          println("${bpSpec.method.name} ${values.joinToString(" ")}")
          if (bpSpec.dumpStack) {
            println(Stack(exitFrame.stackTrace).stackTraceToString())
          }
        }
      } else {
        // We can print values now
        println("${bpSpec.method.name} ${values.joinToString(" ")}")
        if (bpSpec.dumpStack) {
          println(Stack(entryFrame.stackTrace).stackTraceToString())
        }
      }
    }
  }

  // Log breakpoints until CTRL+C
  eprintln("Waiting for breakpoints to be hit (CTRL-C to abort)")
  CountDownLatch(1).await()
}
