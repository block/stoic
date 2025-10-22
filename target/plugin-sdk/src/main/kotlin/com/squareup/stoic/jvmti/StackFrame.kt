package com.squareup.stoic.jvmti

import com.squareup.stoic.threadlocals.jvmti

/**
 * Analog of https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/StackFrame.html
 *
 * This represents a pointer to the Thread's stack frame. When the actual stack frame gets popped
 * this pointer to it becomes invalid, though in practice ART's JVMTI implementation seems pretty
 * good at generating reasonable error messages when you use it after it becomes invalid.
 *
 * The pointer is just the pair of (Thread, frame-height), so if the exact same frame (that is, same
 * method-id, location) then everything will work fine. I'd say it's hacky to depend on that though!
 *
 * TODO: this needs to be rethought - the location currently doesn't necessarily match the height
 *   within the stack. The reason is that the location might correspond to a call (e.g.
 *   method-entry) without a corresponding stack frame. This can happen for example when a native
 *   method is invoked.
 */
class StackFrame(val thread: Thread, val height: Int, val location: Location) {
  val stackTrace
    get(): List<StackTraceElement> {
      return thread.stackTrace.takeLast(height)
    }

  override fun toString(): String {
    return "StackFrame(thread=$thread, location=$location)"
  }

  fun <T> get(variable: LocalVariable<T>): T {
    val thread = Thread.currentThread()
    when (variable.signature) {
      "I",
      "Z",
      "B",
      "S" -> {
        val intRepr = VirtualMachine.nativeGetLocalInt(thread, height, variable.slot)
        return when (variable.signature) {
          "I" -> intRepr
          "Z" -> (intRepr != 0)
          "B" -> intRepr.toByte()
          "S" -> intRepr.toShort()
          else -> throw Throwable("Impossible")
        }
          as T
      }
      "J" -> return VirtualMachine.nativeGetLocalLong(thread, height, variable.slot) as T
      "F" -> return VirtualMachine.nativeGetLocalFloat(thread, height, variable.slot) as T
      "D" -> return VirtualMachine.nativeGetLocalDouble(thread, height, variable.slot) as T
      else -> {
        check(variable.signature.startsWith("L") || variable.signature.startsWith("["))
        return VirtualMachine.nativeGetLocalObject(thread, height, variable.slot) as T
      }
    }
  }

  fun <T> set(variable: LocalVariable<T>, value: T) {
    TODO()
  }

  /**
   * Get a callback when the frame exits.
   *
   * Note: This implementation is based on the MethodExit callback rather than the NotifyFramePop
   * callback. The latter is probably more efficient because it only generates a callback for the
   * frame we are interested in, but it doesn't provide access to the return value.
   *
   * If performance is problematic we could either
   * 1. Expose NotifyFramePop separately, or
   * 2. Filter out callbacks at the native layer
   */
  fun onExit(callback: OnMethodExit) {
    var exitRequest: MethodExitRequest? = null
    exitRequest =
      jvmti.methodExits { frame, value, wasPoppedByException ->
        // In the case of wasPoppedByException=true we may not see a method exit for the current
        // frame
        // Or maybe we see the exit, but its after the frame has already been popped. This would
        // seem
        // to be in violation of
        // https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#MethodExit
        // but its what I see on my Android 14 emulator. So we need to check for `<=` and not simply
        // `==`.
        if (frame.height <= this.height) {
          exitRequest!!.close()
          callback(frame, value, wasPoppedByException)
        }
      }
  }
}
