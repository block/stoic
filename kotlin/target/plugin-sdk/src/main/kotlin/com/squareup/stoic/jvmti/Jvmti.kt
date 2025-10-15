package com.squareup.stoic.jvmti

import com.squareup.stoic.threadlocals.jvmti
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

fun KFunction<*>.forEachInvocation(block: (frame: StackFrame) -> Unit) {
  this.javaMethod!!.forEachInvocation { frame -> block(frame) }
}

fun Method.forEachInvocation(block: (frame: StackFrame) -> Unit) {
  val methodId = VirtualMachine.nativeFromReflectedMethod(this)
  val jvmtiMethod = JvmtiMethod[methodId]
  val location = jvmtiMethod.startLocation
  jvmti.breakpoint(location) { frame -> block(frame) }
}

fun JvmtiMethod.forEachInvocation(block: (frame: StackFrame) -> Unit) {
  jvmti.breakpoint(startLocation) { frame -> block(frame) }
}
