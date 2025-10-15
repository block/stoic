import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.squareup.stoic.helpers.*
import com.squareup.stoic.threadlocals.stoic
import java.util.concurrent.CountDownLatch


/**
 * A Stoic plugin to inject errors into a running process
 */
@Suppress("unused")
fun main(args: Array<String>) {
  if (args.size != 1) {
    usage(1)
  }

  // TODO: support other error types
  when (args[0]) {
    "anr" -> injectAnr()
    "art-oom" -> injectArtOom()
    "error" -> injectMainThreadError()
    "background-error" -> injectBackgroundThreadError()
    else -> usage(1)
  }
}

fun usage(exitCode: Int) {
  eprintln("stoic crasher [anr|art-oom|error|background-error]")
  exit(exitCode)
}

fun injectMainThreadError() {
  stoic.runOnLooper(Looper.getMainLooper(), null) {
    eprintln("Injecting main thread error...")
    throw Exception("This is a main thread error.")
  }
}

/**
 * This creates a HandlerThread named "injected-error".
 * Post a message to the HandlerThread that will throw an exception.
 * This will cause the thread to crash.
 */
fun injectBackgroundThreadError() {
  HandlerThread("injected-error").also {
    it.start()
    stoic.runOnLooper(it.looper, null) {
      eprintln("Injecting background thread error...")
      throw Exception("This is a background thread error.")
    }
  }
}

/**
 * This injects an ART OutOfMemory error. The naming art-oom is specific to avoid confusion with
 * lmkd-style OOMs, which are an entirely different class of problem.
 */
// TODO: provide options for single large allocation vs multiple small allocations
// TODO: provide options for main-thread
fun injectArtOom() {
  thread {
    val listOfArrays = mutableListOf<ByteArray>()
    while (true) {
      listOfArrays.add(ByteArray(Int.MAX_VALUE))
      val total = Int.MAX_VALUE.toLong() * listOfArrays.size / (1024 * 1024 * 1024)
      // We print a hash to prevent any possible compiler optimizations from being applied (not that
      // I've seen any).
      eprintln("Total allocated: $total GB. Hash=${listOfArrays.hashCode()}")
    }
  }
}

fun injectAnr() {

  // We acquire two locks from two different threads, opposite ordering, to cause a deadlock
  // This is the nicest type of ANR to debug.
  eprintln("Acquiring locks to hang the main thread...")
  val lock1 = Any()
  val latch1 = CountDownLatch(1)
  val lock2 = Any()
  val latch2 = CountDownLatch(1)
  HandlerThread("ANR Thread").also {
    it.start()
    Handler(it.looper).post {
      synchronized(lock1) {
        latch2.countDown()
        latch1.await()
        synchronized(lock2) {
          throw Exception("This shouldn't be possible")
        }
      }
    }
  }
  Handler(Looper.getMainLooper()).post {
    synchronized(lock2) {
      latch1.countDown()
      latch2.await()
      synchronized(lock1) {
        throw Exception("This shouldn't be possible")
      }
    }
  }

  eprintln("Main thread hung. Send input to trigger ANR detection.")

  // We can't run `monkey` from inside our process.
  // TODO: It'd be nice to have the ability to run preamble/postamble plugin code on the host
  // This would give us the ability trigger ANR detection automatically.
}
