package com.squareup.stoic.jvmti.magic

import com.squareup.stoic.LruCache
import com.squareup.stoic.jvmti.JvmtiClass
import com.squareup.stoic.jvmti.JvmtiMethod

class MagicMethods(private val obj: Any?, private val clazz: JvmtiClass) {
  operator fun get(name: String, jvmSig: String? = null): MagicMethod {
    val key = Triple(clazz, name, jvmSig)
    val method = cache[key]
    val resolvedMethod = if (method == null) {
      val resolvedMethod = try {
        resolve(clazz, name, jvmSig)
      } catch (e: NoSuchMethodException) {
        //eprintln("clazz: ${clazz.name}")
        //for (m in clazz.declaredMethods.sortedBy { it.name }) {
        //  eprintln("method: ${m.name} ${m.modifiers} ${m.parameterTypes.asList()}")
        //}
        throw NoSuchMethodException("$clazz $name $jvmSig not found")
      }

      cache[key] = resolvedMethod
      resolvedMethod
    } else {
      method
    }

    return MagicMethod(obj, resolvedMethod)
  }

  companion object {
    private val cache = LruCache<Triple<JvmtiClass, String, String?>, JvmtiMethod>(1024)

    private fun resolve(clazz: JvmtiClass, name: String, jvmSig: String?): JvmtiMethod {
      val candidates = clazz.declaredMethods.filter {
        if (jvmSig == null) {
          name == it.name
        } else {
          name == it.name && jvmSig == it.signature
        }
      }

      return if (candidates.size == 1) {
        candidates[0]
      } else if (candidates.size > 1) {
        // TODO: also consider superclass candidates
        val sigs = candidates.map { it.signature }
        throw Exception("method[$name, $jvmSig] - use method[$name, sig] syntax, where sig is any of $sigs")
      } else {
        if (clazz.clazz.superclass != null) {
          resolve(JvmtiClass[clazz.clazz.superclass], name, jvmSig)
        } else {
          throw NoSuchMethodException()
        }
      }
    }
  }
}
