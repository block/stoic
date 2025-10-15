package com.squareup.stoic

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.squareup.stoic.jvmti.BreakpointRequest
import com.squareup.stoic.jvmti.Location
import com.squareup.stoic.jvmti.MethodEntryRequest
import com.squareup.stoic.jvmti.MethodExitRequest
import com.squareup.stoic.jvmti.OnBreakpoint
import com.squareup.stoic.jvmti.OnMethodEntry
import com.squareup.stoic.jvmti.OnMethodExit
import com.squareup.stoic.jvmti.VirtualMachine
import com.squareup.stoic.threadlocals.stoic
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// internalStoic should only be used in callWith. stoic (as defined in ThreadLocals) should not be
// used
internal val internalStoic = ThreadLocal<Stoic>()

class StoicJvmti private constructor() {
  fun <T> instances(clazz: Class<T>, includeSubclasses: Boolean = true): Array<out T> {
    return VirtualMachine.nativeInstances(clazz, includeSubclasses)
  }

  fun <T> subclasses(clazz: Class<T>): Array<Class<out T>> {
    return VirtualMachine.nativeSubclasses(clazz)
  }

  fun breakpoint(location: Location, onBreakpoint: OnBreakpoint): BreakpointRequest {
    val pluginStoic = stoic
    return VirtualMachine.eventRequestManager.createBreakpointRequest(location) { frame ->
      pluginStoic.callWith {
        onBreakpoint(frame)
      }
    }
  }

  fun methodEntries(onMethodEntry: OnMethodEntry): MethodEntryRequest {
    val pluginStoic = stoic
    return VirtualMachine.eventRequestManager.createMethodEntryRequest(Thread.currentThread()) { frame ->
      pluginStoic.callWith {
        onMethodEntry(frame)
      }
    }
  }

  fun methodExits(onMethodExit: OnMethodExit): MethodExitRequest {
    val pluginStoic = stoic
    return VirtualMachine.eventRequestManager.createMethodExitRequest(Thread.currentThread()) { frame, value, wasPoppedByException ->
      pluginStoic.callWith {
        onMethodExit(frame, value, wasPoppedByException)
      }
    }
  }

  val virtualMachine: VirtualMachine get() {
    return VirtualMachine
  }

  companion object {
    @Volatile var privateIsInitialized: Boolean = false
    private val stoicJvmti: StoicJvmti = StoicJvmti()
    val isInitialized: Boolean get() = privateIsInitialized

    fun get() : StoicJvmti {
      // We'd like to allow for plugins that get run without jvmti, but they'll have to be aware that
      // it's unsafe for them to access jvmti
      assert(isInitialized)
      return stoicJvmti
    }

    // Call this if/when we registerNatives
    fun markInitialized() {
      privateIsInitialized = true
    }
  }
}

class Stoic(
  val env: Map<String, String>,
  val stdin: InputStream,
  val stdout: PrintStream,
  val stderr: PrintStream,
) {
  companion object {
    const val DEFAULT_TIMEOUT_MS = 60_000L
  }

  val jvmti: StoicJvmti
    get() = StoicJvmti.get()

  // This should be the only place in this file that uses internalStoic
  fun <T> callWith(forwardUncaught: Boolean = false, printErrors: Boolean = true, callable: Callable<T>): T {
    val oldStoic = internalStoic.get()
    internalStoic.set(this)
    try {
      return callable.call()
    } catch (t: Throwable) {
      if (forwardUncaught) {
        // TODO: This should become the default when I have it working
        stderr.println(
          "TODO: forward uncaught exception and bring down the plugin without killing the process"
        )
        if (printErrors) {
          stderr.println(t.stackTraceToString())
        }
        throw t
      } else {
        if (printErrors) {
          stderr.println(t.stackTraceToString())
        }
        throw t
      }
    } finally {
      internalStoic.set(oldStoic)
    }
  }

  fun <T> wrapCallable(callable: Callable<T>): Callable<T> {
    return Callable {
      callWith {
        callable.call()
      }
    }
  }

  fun wrapRunnable(runnable: Runnable): Runnable {
    return Runnable {
      callWith {
        runnable.run()
      }
    }
  }

  /**
   * runOnLooper/runOnExecutor/thread
   *
   * These provide mechanisms for running stoic plugin code on different threads. These should be
   * used instead of the raw looper/executor/thread APIs because they will handle forwarding the
   * stoic thread-local, and reporting uncaught exceptions to stderr.
   *
   * They also provide an optional timeoutMs that can be used to wait for the asynchronous operation
   * to complete. If timeoutMs is provided then any Throwable will be caught and printed to stderr.
   *
   * TODO: Run-delayed and Future variants
   *
   * If you are using a custom async mechanism you can provide support for it by using
   * using wrapRunnable (for non-blocking behavior) or LatchedRunnable (for blocking)
   */

  fun runOnLooper(looper: Looper, timeoutMs: Long? = DEFAULT_TIMEOUT_MS, runnable: Runnable) {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      Handler(looper).post(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
    } else {
      Handler(looper).post(wrapRunnable(runnable))
    }
  }

  fun runOnMainLooper(timeoutMs: Long? = DEFAULT_TIMEOUT_MS, runnable: Runnable) {
    runOnLooper(Looper.getMainLooper(), timeoutMs, runnable)
  }

  fun runOnExecutor(executor: Executor, timeoutMs: Long? = DEFAULT_TIMEOUT_MS, runnable: Runnable) {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      executor.execute(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
    } else {
      executor.execute(wrapRunnable(runnable))
    }
  }

  fun thread(timeoutMs: Long? = DEFAULT_TIMEOUT_MS, runnable: Runnable): Thread {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      val t = rawStoicThread(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
      return t // The thread will be done by this time
    } else {
      return kotlin.concurrent.thread {
        wrapRunnable(runnable).run()
      }
    }
  }

  private fun rawStoicThread(runnable: Runnable): Thread {
    return kotlin.concurrent.thread {
      callWith { runnable.run() }
    }
  }

  fun getenv(name: String): String? {
    return env[name] ?: System.getenv(name)
  }

  /**
   * The src path of the shebang script used to invoke stoic, or null if stoic wasn't invoked via a
   * shebang.
   */
  val shebangSrcPath: String? get() {
    return getenv("STOIC_SHEBANG_SRC_PATH")
  }

  // It'd be nice to allow plugins to use System.in/out/err directly, but it's easy to end up with
  // weird problems when you System.setOut/setErr so I abandoned this approach. I'd need to be
  // careful I didn't write to System.err anywhere. Here's a StackOverflowError I encountered
  // (it's worse because I catch Throwable and print the result to stderr so I wasn't even seeing
  // that). So I abandoned this approach.
  // TODO: Allow plugins to use System.in/out/err by rewriting their bytecode, AOP-style to use
  // thread-local stdin/stdout/sterr that get propagated by runWithStoic.
  //
  //  ...
  //	at java.io.PrintStream.write(PrintStream.java:503)
  //	at com.squareup.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  //	at java.io.PrintStream.write(PrintStream.java:503)
  //	at com.squareup.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  //	at java.io.PrintStream.write(PrintStream.java:503)
  // 	at com.squareup.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  // 	at java.io.PrintStream.write(PrintStream.java:503)
  // 	at sun.nio.cs.StreamEncoder.writeBytes(StreamEncoder.java:221)
  // 	at sun.nio.cs.StreamEncoder.implWrite(StreamEncoder.java:282)
  // 	at sun.nio.cs.StreamEncoder.write(StreamEncoder.java:125)
  // 	at java.io.OutputStreamWriter.write(OutputStreamWriter.java:207)
  // 	at java.io.BufferedWriter.flushBuffer(BufferedWriter.java:129)
  // 	at java.io.PrintStream.write(PrintStream.java:553)
  // 	at java.io.PrintStream.print(PrintStream.java:698)
  // 	at java.io.PrintStream.println(PrintStream.java:835)
  // 	at com.squareup.stoic.common.StoicKt.log(Stoic.kt:23)
  // 	at com.squareup.stoic.common.StoicKt.logDebug(Stoic.kt:28)
  // 	at com.squareup.stoic.common.NamedPipeServer.accept(NamedPipeServer.kt:135)
  // 	at com.squareup.stoic.target.runtime.AndroidServerJarKt.main(AndroidServerJar.kt:51)
  //

  fun exitPlugin(code: Int) {
    throw ExitCodeException(code)
  }
}

/**
 * A runnable that waits for itself to run. This is used to run code "asynchronously" and wait for
 * it to complete.
 */
class LatchedRunnable(stoicInstance: Stoic, runnable: Runnable) : Runnable {
  private val wrappedRunnable = stoicInstance.wrapRunnable(runnable)
  private val startUptimeMillis = SystemClock.uptimeMillis()
  private val runnableStartUptimeMillisAtomic = AtomicLong(-1)
  private val latch = CountDownLatch(1)
  private val crash = AtomicReference<Throwable>()
  private val ranOnThread = AtomicReference<Thread>()

  override fun run() {
    runnableStartUptimeMillisAtomic.set(SystemClock.uptimeMillis())
    ranOnThread.set(Thread.currentThread())
    try {
      wrappedRunnable.run()
    } catch (t: Throwable) {
      crash.set(t)
    } finally {
      latch.countDown()
    }
  }

  fun waitFor(timeoutMs: Long) {
    if (!latch.await(timeoutMs, MILLISECONDS)) {
      val runnableStartUptimeMillis = runnableStartUptimeMillisAtomic.get()
      val msg = if (runnableStartUptimeMillis <= 0) {
        "Unable to schedule $wrappedRunnable within ${timeoutMs}ms"
      } else {
        val scheduleDelay = runnableStartUptimeMillis - startUptimeMillis
        "$wrappedRunnable (scheduled after ${scheduleDelay}ms) did not complete within ${timeoutMs}ms"
      }
      throw TimeoutException(msg).also { e ->
        ranOnThread.get()?.stackTrace?.also { e.stackTrace = it }
      }
    }
  }
}

// Could be useful to someone writing code that runs on either Android/JVM
val isAndroid = try {
  Build.VERSION.SDK_INT > -1
} catch (e: Throwable) {
  false
}

fun <T> highlander(list: List<T>): T {
  if (list.size != 1) {
    throw IllegalArgumentException("There can be only one: $list")
  }

  return list[0]
}

class Stack(stackTrace: Array<StackTraceElement>): Throwable() {
  constructor(stackTrace: List<StackTraceElement>): this(stackTrace.toTypedArray())
  init {
    this.stackTrace = stackTrace
  }
}

class LruCache<K, V>(private val cacheSize: Int) : LinkedHashMap<K, V>(cacheSize, 0.75f, true) {
  override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
    // Remove the eldest entry if the size exceeds the predefined cache size
    return size > cacheSize
  }
}