import android.os.Build
import com.squareup.stoic.jvmti.JvmtiException
import com.squareup.stoic.jvmti.JvmtiMethod
import com.squareup.stoic.jvmti.VirtualMachine
import com.squareup.stoic.trace.Include
import com.squareup.stoic.trace.IncludeEach
import com.squareup.stoic.trace.OmitThis
import com.squareup.stoic.trace.identityString
import com.squareup.stoic.trace.printMethodTree
import com.squareup.stoic.trace.rules
import com.squareup.stoic.trace.trace
import com.squareup.stoic.trace.traceExpect
import com.squareup.stoic.helpers.*
import com.squareup.stoic.threadlocals.stoic

fun main(args: Array<String>) {
  testDuplicateArguments()
  testTrace()
  testMethodEntry()
  testMethodExit()
}

// Verify that we don't include duplicate arguments. The local variable table may contain duplicate
// entries for some slots. If we see duplicate entries, we must prefer ones with non-null names.
// This verifies that we handle it correctly with a method known to suffer from this problem.
fun testDuplicateArguments() {
  eprintln("testDuplicateArguments")
  if (Build.VERSION.SDK_INT < 33) {
    // testDuplicateArguments uses a method signature that changed between API levels
    // The signature we test for is only available on API 32+
    eprintln("skipping (requires API 33+)")
    return
  }

  val method = JvmtiMethod.bySig(
    "android/view/AccessibilityInteractionController\$AccessibilityNodePrefetcher.prefetchAccessibilityNodeInfos(Landroid/view/View;Landroid/view/accessibility/AccessibilityNodeInfo;Ljava/util/List;)V"
  )
  check(method.arguments.map { it.name } == listOf("this", "view", "root", "outInfos"))
}

fun testTrace() {
  eprintln("testTrace")
  if (Build.VERSION.SDK_INT < 29) {
    eprintln("skipping (requires API 29+ for IterateOverInstancesOfClass)")
    return
  }

  // Run ahead of time to capture clinit
  Foo.bar()
  Bar.bar()

  traceExpect(
    "Foo.bar(...)",
    "Foo" to Include) {
    Foo.bar()
  }

  traceExpect(
    "Bar\$Companion.bar(...)",
    "Bar\$Companion" to Include) {
    Bar.bar()
  }

  traceExpect(
    """
      Foo.bar(
        this = ${identityString(Foo)},
        baz = 5,
      )
    """.trimIndent(),
    "Foo" to IncludeEach) {
    Foo.bar(5)
  }

  traceExpect(
    """
      Foo.bar(
        baz = 5,
      )
    """.trimIndent(),
    "Foo" to rules(OmitThis)
  ) {
    Foo.bar(5)
  }

  // TODO: KotlinRepr - "lol"
  traceExpect(
    """
      Foo.bar(
        baz = 5,
        taz = lol,
      )
    """.trimIndent(),
    "Foo" to rules(OmitThis)
  ) {
    Foo.bar(5, "lol")
  }

  traceExpect(
    """
      Bar${'$'}Companion.foo(
        b = Bar {
          baz = 42,
        },
      )
    """.trimIndent(),
    "Bar\$Companion" to rules(
      "foo" to rules(
        "b" to IncludeEach
      )
    )
  ) {
    Bar.foo(Bar(42))
  }
}

object Foo {
  fun bar() {}

  fun bar(baz: Int) {}

  fun bar(baz: Int, taz: String) { }
}

class Bar(val baz: Int) {
  companion object {
    fun bar() { }
    fun foo(b: Bar) { }
  }
}

fun testMethodEntry() {
  eprintln("testMethodEntry")
  if (Build.VERSION.SDK_INT < 29) {
    eprintln("skipping (requires API 29+)")
    return
  }

  var methodEntryCalled = false

  val testMethod = JvmtiMethod.bySig("MainKt.testMethodEntryHelper()V")

  // Test method entry
  val entryRequest = VirtualMachine.eventRequestManager.createMethodEntryRequest(Thread.currentThread()) { frame ->
    if (frame.location.method.methodId == testMethod.methodId) {
      eprintln("Method entry callback triggered")
      methodEntryCalled = true
    }
  }

  // Call the test method
  testMethodEntryHelper()

  // Clean up
  VirtualMachine.eventRequestManager.deleteEventRequest(entryRequest)

  check(methodEntryCalled) { "Method entry callback was not called" }

  eprintln("testMethodEntry passed")
}

fun testMethodExit() {
  eprintln("testMethodExit")
  if (Build.VERSION.SDK_INT < 29) {
    eprintln("skipping (requires API 29+)")
    return
  }

  var methodExitCalled = false

  val testMethod = JvmtiMethod.bySig("MainKt.testMethodExitHelper()V")

  // Test method exit
  val exitRequest = VirtualMachine.eventRequestManager.createMethodExitRequest(Thread.currentThread()) { frame, value, wasPoppedByException ->
    if (frame.location.method.methodId == testMethod.methodId) {
      eprintln("Method exit callback triggered")
      methodExitCalled = true
    }
  }

  // Call the test method
  testMethodExitHelper()

  // Clean up
  VirtualMachine.eventRequestManager.deleteEventRequest(exitRequest)

  check(methodExitCalled) { "Method exit callback was not called" }

  eprintln("testMethodExit passed")
}

fun testMethodEntryHelper() {
  // This is just a helper method to test entry callback
}

fun testMethodExitHelper() {
  // This is just a helper method to test exit callback
}