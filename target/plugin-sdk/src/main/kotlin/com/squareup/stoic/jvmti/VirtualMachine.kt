package com.squareup.stoic.jvmti

import com.squareup.stoic.highlander
import java.lang.reflect.Field
import java.lang.reflect.Method

// a jmethodID
typealias JMethodId = Long

// a jfieldID
typealias JFieldId = Long

// a jlocation
typealias JLocation = Long


/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/VirtualMachine.html
 */
object VirtualMachine {
  val eventRequestManager: EventRequestManager = EventRequestManager()

  @JvmStatic
  external fun <T> nativeInstances(clazz: Class<T>, includeSubclasses: Boolean): Array<out T>

  @JvmStatic
  external fun <T> nativeSubclasses(clazz: Class<T>): Array<Class<out T>>

  @JvmStatic
  external fun nativeGetMethodId(clazz: Class<*>, methodName: String, methodSignature: String): JMethodId

  @JvmStatic
  external fun nativeSetBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeClearBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeGetLocalVariables(jmethodId: JMethodId): Array<LocalVariable<*>>

  @JvmStatic
  external fun nativeGetMethodCoreMetadata(method: JvmtiMethod)

  @JvmStatic
  external fun nativeGetFieldCoreMetadata(method: JvmtiField)

  @JvmStatic
  external fun nativeGetLocalObject(thread: Thread, height: Int, slot: Int): Any

  @JvmStatic
  external fun nativeGetLocalInt(thread: Thread, height: Int, slot: Int): Int

  @JvmStatic
  external fun nativeGetLocalLong(thread: Thread, height: Int, slot: Int): Long

  @JvmStatic
  external fun nativeGetLocalFloat(thread: Thread, height: Int, slot: Int): Float

  @JvmStatic
  external fun nativeGetLocalDouble(thread: Thread, height: Int, slot: Int): Double

  @JvmStatic
  external fun nativeGetClassMethods(clazz: Class<*>): Array<JvmtiMethod>

  @JvmStatic
  external fun nativeGetClassFields(clazz: Class<*>): Array<JvmtiField>

  //@JvmStatic
  //external fun nativeInvokeMethod(isStatic: Boolean, methodId: JMethodId, args: Array<Any>)

  // Returns either java.lang.reflect.Method or java.lang.reflect.Constructor
  @JvmStatic
  external fun nativeToReflectedMethod(clazz: Class<*>, methodId: JMethodId, isStatic: Boolean): Any

  @JvmStatic
  external fun nativeToReflectedField(clazz: Class<*>, fieldId: JFieldId, isStatic: Boolean): Field

  // TODO: the actual API also returns genericSignature - this should probably return a pair of
  // Strings
  @JvmStatic
  external fun nativeGetClassSignature(clazz: Class<*>): String

  @JvmStatic
  external fun nativeMethodEntryCallbacks(thread: Thread, isEnabled: Boolean)

  @JvmStatic
  external fun nativeMethodExitCallbacks(thread: Thread, isEnabled: Boolean)

  @JvmStatic
  external fun nativeFromReflectedMethod(method: Method): JMethodId

  // Callback from native
  @JvmStatic
  fun nativeCallbackOnBreakpoint(jmethodId: JMethodId, jlocation: JLocation, frameCount: Int) {
    val method = JvmtiMethod[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onBreakpoint(frame)
  }

  @JvmStatic
  fun nativeCallbackOnMethodEntry(jmethodId: JMethodId, jlocation: JLocation, frameCount: Int) {
    val method = JvmtiMethod[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onMethodEntry(frame)
  }

  @JvmStatic
  fun nativeCallbackOnMethodExit(
      jmethodId: JMethodId,
      jlocation: JLocation,
      frameCount: Int,
      value: Any?,
      wasPoppedByException: Boolean) {
    val method = JvmtiMethod[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onMethodExit(frame, value, wasPoppedByException)
  }
}
