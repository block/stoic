package com.squareup.stoic.jvmti.magic

import com.squareup.stoic.LruCache
import com.squareup.stoic.highlander
import com.squareup.stoic.jvmti.JvmtiClass
import com.squareup.stoic.jvmti.JvmtiField

class MagicFields(private val obj: Any?, private val clazz: JvmtiClass) {

  operator fun get(name: String, className: String? = null): Any? {
    return lookup(clazz, name, className).get(obj)
  }

  operator fun set(name: String, className: String? = null, value: Any?) {
    lookup(clazz, name, className).set(obj, value)
  }

  companion object {
    private val cache = LruCache<Triple<JvmtiClass, String, String?>, JvmtiField>(1024)

    private fun lookup(clazz: JvmtiClass, name: String, className: String?): JvmtiField {
      val key = Triple(clazz, name, className)
      val field = cache[key]
      return if (field == null) {
        val resolvedField = resolve(clazz, name, className)
        cache[key] = resolvedField
        resolvedField
      } else {
        field
      }
    }

    private fun resolve(clazz: JvmtiClass, name: String, signature: String?): JvmtiField {
      var filteredByName: List<JvmtiField>
      var baseClazz = clazz
      while (true) {
        filteredByName = filter(baseClazz, name, signature)
        if (filteredByName.isNotEmpty()) { break }
        else if (baseClazz.clazz.superclass == null) { break }

        baseClazz = JvmtiClass[baseClazz.clazz.superclass]
      }

      if (filteredByName.isEmpty()) {
        // Re-throw with the derived-most class name
        throw NoSuchFieldException("No field $name on $clazz")
      } else {
        return highlander(filteredByName)
      }
    }

    private fun filter(clazz: JvmtiClass, name: String, signature: String?): List<JvmtiField> {
      return clazz.declaredFields.filter {
        if (signature != null) {
          it.name == name && it.signature == signature
        } else {
          it.name == name
        }
      }
    }
  }
}
