package com.squareup.stoic.jvmti

import com.squareup.stoic.LruCache
import com.squareup.stoic.highlander
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Method.html
 *
 * See https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#method for JVMTI method
 * functions
 */
class JvmtiMethod private constructor(val methodId: JMethodId) {
  init {
    check(methodId != 0L)
  }

  // These are fetched by nativeGetMethodCoreMetadata
  private var privateClazz: Class<*>? = null
  private var privateName: String? = null
  private var privateSignature: String? = null
  private var privateGeneric: String? = null
  private var privateStartLocation: JLocation = Long.MIN_VALUE
  private var privateEndLocation: JLocation = Long.MIN_VALUE
  private var privateArgsSize = Int.MIN_VALUE
  private var privateMaxLocals = Int.MIN_VALUE
  private var privateModifiers: Int = -1
  private var privateReflected: Any? = null

  // This is fetched by nativeGetLocalVariables(jmethodId).toList()
  private var privateVariables: List<LocalVariable<*>>? = null

  val clazz: Class<*>
    get() {
      val result = privateClazz
      if (result != null) {
        return result
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateClazz!!
    }

  val name: String
    get() {
      val result = privateName
      if (result != null) {
        return result
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateName!!
    }

  val signature: String
    get() {
      val result = privateSignature
      if (result != null) {
        return result
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateSignature!!
    }

  /** The start of the method */
  val startLocation
    get(): Location {
      val result = privateStartLocation
      if (result != Long.MIN_VALUE) {
        return Location(this, result)
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return Location(this, privateStartLocation)
    }

  val argsSize
    get(): Int {
      val result = privateArgsSize
      if (result != Int.MIN_VALUE) {
        return result
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateArgsSize
    }

  val maxLocals
    get(): Int {
      val result = privateMaxLocals
      if (result != Int.MIN_VALUE) {
        return result
      }

      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateMaxLocals
    }

  val modifiers
    get(): Int {
      val result = privateModifiers
      if (result != -1) {
        return result
      }

      // -1 is an invalid value for modifiers (it's all modifiers set), so if we see -1 we know we
      // need to fetch
      VirtualMachine.nativeGetMethodCoreMetadata(this)
      return privateModifiers
    }

  val reflected
    get(): Any {
      val result = privateReflected
      if (result != null) {
        return result
      }

      val lookup =
        VirtualMachine.nativeToReflectedMethod(clazz, methodId, Modifier.isStatic(modifiers))
      privateReflected = lookup
      return lookup
    }

  val arguments: List<LocalVariable<*>>
    get() {
      try {
        return variables.filter { it.slot >= maxLocals - argsSize }
      } catch (e: JvmtiException) {
        if (e.errorCode != JVMTI_ERROR_ABSENT_INFORMATION) {
          throw e
        }

        val methodOrCtor = VirtualMachine.nativeToReflectedMethod(clazz, methodId, false)
        val parameterTypes =
          if (methodOrCtor is Constructor<*>) {
            methodOrCtor.parameterTypes
          } else {
            (methodOrCtor as Method).parameterTypes
          }
        var slot = maxLocals - argsSize
        val vars = mutableListOf<LocalVariable<*>>()
        for (param in parameterTypes) {
          val slotSize =
            when (param) {
              java.lang.Long.TYPE,
              java.lang.Double.TYPE -> 2
              else -> 1
            }
          vars.add(
            LocalVariable<Any>(
              0,
              1,
              null,
              VirtualMachine.nativeGetClassSignature(param),
              null,
              slot,
            )
          )

          slot += slotSize
        }

        return vars
      }
    }

  @get:Synchronized
  val variables: List<LocalVariable<*>>
    get() {
      val result = privateVariables
      if (result != null) {
        return result
      }

      // We need to remove duplicate variables (ones that have the same slot), preferring ones with
      // non-null names. See testDuplicateArguments for an example of a method with duplicate
      // variables. We prefer named variables.
      val locals = VirtualMachine.nativeGetLocalVariables(methodId)
      val slotToIndex = mutableMapOf<Int, Int>()
      for (i in locals.indices) {
        val slot = locals[i].slot
        val dupeIndex = slotToIndex[slot]
        if (dupeIndex == null) {
          slotToIndex[slot] = i
          continue
        }

        if (locals[dupeIndex].name == null && locals[i].name != null) {
          slotToIndex[slot] = i
        }
      }

      privateVariables = slotToIndex.entries.sortedBy { it.key }.map { locals[it.value] }

      return privateVariables!!
    }

  fun variablesByName(name: String): List<LocalVariable<*>> {
    return variables.filter { it.name == name }
  }

  fun <T> argumentByName(name: String): LocalVariable<T> {
    return highlander(arguments.filter { it.name == name }) as LocalVariable<T>
  }

  fun invokeStatic(vararg args: Any?): Any? {
    return (reflected as java.lang.reflect.Method).invoke(null, *args)
  }

  fun invokeCtor(vararg args: Any?): Any? {
    return (reflected as Constructor<*>).newInstance(*args)
  }

  fun invokeNormal(thiz: Any, vararg args: Any?): Any? {
    return (reflected as java.lang.reflect.Method).invoke(thiz, *args)
  }

  val jvmtiClass
    get() = JvmtiClass[clazz]

  val simpleQualifiedName = "${jvmtiClass.simpleName}.$name"

  override fun toString(): String {
    return "JvmtiMethod($simpleQualifiedName$signature)"
  }

  companion object {
    private val cache = LruCache<JMethodId, JvmtiMethod>(8192)

    @Synchronized
    operator fun get(methodId: JMethodId): JvmtiMethod {
      var method = cache[methodId]
      if (method == null) {
        method = JvmtiMethod(methodId)
        cache[methodId] = method
      }

      return method
    }

    fun bySig(sig: String): JvmtiMethod {
      val match = Regex("""([^.]+)\.(\w+)(\([^()]*\)[^()]*)""").matchEntire(sig)
      check(match != null) { "Invalid sig: '$sig'" }
      val classSig = match.groupValues[1]
      val methodName = match.groupValues[2]
      val methodSig = match.groupValues[3]
      return JvmtiClass.bySig(classSig).declaredMethod(methodName, methodSig)
    }
  }
}
