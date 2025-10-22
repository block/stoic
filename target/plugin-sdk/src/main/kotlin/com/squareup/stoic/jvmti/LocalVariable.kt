package com.squareup.stoic.jvmti

/**
 * Analog to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/LocalVariable.html
 *
 * This maps directly to the jvmtiLocalVariableEntry struct - it's populated via a call to JVMTI's
 * GetLocalVariableTable
 */
class LocalVariable<T>(
  val startLocation: JLocation,
  val length: Int,
  val name: String?,
  val signature: String,
  val genericSignature: String?,
  val slot: Int,
) {}
