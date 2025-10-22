// This is a template for your new plugin. Change it however you'd like.

// You may import whatever Android APIs you need
import android.os.Process.myPid
import android.os.Process.myTid

// Import helpers.* to provide access to alternate, plugin-friendly API
// implementations. For example, println normally prints to logcat, but
// helpers.println will tunnel through to Stoic's stdout.
import com.squareup.stoic.helpers.*

// Multiple instances of stoic may be active simultaneously - access the one
// that started your plugin via this thread-local
import com.squareup.stoic.threadlocals.stoic

fun main(args: Array<String>) {
  // You may access the arguments passed on the command-line
  println("main(${args.toList()})")

  // Stoic plugins run on the main thread by default.
  // Use runOnLooper/runOnExecutor/runOnThread for running on other threads.
  // These will automatically propagate threadlocals
  println("Plugin running in process PID=${myPid()} on thread TID=${myTid()}")

  // If you wish to exit with an error-code other than zero, you can call
  // exitPlugin explicitly.
  //exitPlugin(1)
}
