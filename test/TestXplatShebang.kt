#!/usr/bin/env stoic shebang --allow-stoic-options pkg,host,no-host --
package shebang

import android.app.Application
import com.squareup.stoic.isAndroid
import com.squareup.stoic.jvmti
import com.squareup.stoic.print.*

fun main(args: List<String>): Int {
  val pkg =
    if (isAndroid) {
      val apps = jvmti.instances(Application::class.java)
      apps[0].packageName
    } else {
      "jvm"
    }
  println("pkg: $pkg, args: $args")
  return 0
}
