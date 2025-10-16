import android.os.Build
import com.squareup.stoic.jvmti.JvmtiMethod
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
}

// Verify that we don't include duplicate arguments. The local variable table may contain duplicate
// entries for some slots. If we see duplicate entries, we must prefer ones with non-null names.
// This verifies that we handle it correctly with a method known to suffer from this problem.
fun testDuplicateArguments() {
  eprintln("testDuplicateArguments")
  if (Build.VERSION.SDK_INT < 30) {
    // testDuplicateArguments uses a method signature that changed between API levels
    // The signature we test for is only available on API 30+ (Android 11+)
    eprintln("skipping (requires API 30+)")
    return
  }

  val method = JvmtiMethod.bySig(
    "android/view/AccessibilityInteractionController\$AccessibilityNodePrefetcher.prefetchAccessibilityNodeInfos(Landroid/view/View;Landroid/view/accessibility/AccessibilityNodeInfo;Ljava/util/List;)V"
  )
  check(method.arguments.map { it.name } == listOf("this", "view", "root", "outInfos"))
}

fun testTrace() {
  eprintln("testTrace")

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

