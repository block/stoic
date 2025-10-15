package com.squareup.stoic.jvmti

import com.squareup.stoic.LruCache
import com.squareup.stoic.highlander

class JvmtiClass private constructor(val clazz: Class<*>) {
  private var privateDeclaredFields: List<JvmtiField>? = null
  private var privateDeclaredMethods: List<JvmtiMethod>? = null

  val simpleName get(): String = clazz.name.substringAfterLast('.')

  val declaredFields get(): List<JvmtiField> {
    synchronized(this) {
      return privateDeclaredFields ?: run {
        val result = VirtualMachine.nativeGetClassFields(clazz).toList()
        privateDeclaredFields = result
        result
      }
    }
  }

  val declaredMethods get(): List<JvmtiMethod> {
    synchronized(this) {
      return privateDeclaredMethods ?: run {
        val result = VirtualMachine.nativeGetClassMethods(clazz).toList()
        privateDeclaredMethods = result
        result
      }
    }
  }

  fun declaredMethod(name: String, signature: String): JvmtiMethod {
    val filteredMethods = declaredMethods.filter { it.name == name && it.signature == signature }
    if (filteredMethods.isNotEmpty()) {
      return highlander(filteredMethods)
    }

    val filteredByName = declaredMethods.filter { it.name == name }
    if (filteredByName.isNotEmpty()) {
      val signatures = filteredByName.map { "${it.signature}\n" }
      throw NoSuchMethodException(
        """
          Method ${clazz.name}.$name$signature does not exist. ${clazz.name}.$name with the
          following signatures exist:
        """.trimIndent() + "\n$signatures"
      )
    } else {
      val methodNames = declaredMethods.map { "${it.name}\n" }.toSet()
      throw NoSuchMethodException("""
            Method $clazz.$name does not exist. $clazz has declared methods with the following names:
          """.trimIndent() + "\n$methodNames")
    }
  }

  fun declaredField(name: String, signature: String): JvmtiField {
    return highlander(declaredFields.filter { it.name == name && it.signature == signature })
  }

  companion object {
    private val cache = LruCache<Class<*>, JvmtiClass>(8192)

    @Synchronized
    operator fun get(clazz: Class<*>): JvmtiClass {
      var jvmtiClass = cache[clazz]
      if (jvmtiClass == null) {
        jvmtiClass = JvmtiClass(clazz)
        cache[clazz] = jvmtiClass
      }

      return jvmtiClass
    }

    fun bySig(signature: String, classLoader: ClassLoader? = null): JvmtiClass {
      when (signature) {
        "Z" -> return JvmtiClass[Boolean::class.java]
        "I" -> return JvmtiClass[Int::class.java]
        "J" -> return JvmtiClass[Long::class.java]
      }

      val classNameWithDots = signature.replace('/', '.')
      val className = if (classNameWithDots.endsWith(';')) {
        classNameWithDots.removePrefix("L").removeSuffix(";")
      } else {
        classNameWithDots
      }

      val clazz = if (classLoader == null) {
        Thread.currentThread().contextClassLoader.loadClass(className)
      } else {
        classLoader.loadClass(className)
      }
      return JvmtiClass[clazz]
    }
  }
}