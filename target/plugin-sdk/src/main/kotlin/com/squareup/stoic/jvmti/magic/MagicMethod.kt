package com.squareup.stoic.jvmti.magic

import com.squareup.stoic.jvmti.JvmtiMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

class MagicMethod(private val obj: Any?, val method: JvmtiMethod) {
  operator fun invoke(vararg params: Any?): Any? {
    try {
      if (method.name == "<init>") {
        return method.invokeCtor(*params)
      } else if (Modifier.isStatic(method.modifiers)) {
        check(obj == null)
        return method.invokeStatic(*params)
      } else {
        return method.invokeNormal(obj!!, *params)
      }
    } catch (e: InvocationTargetException) {
      throw e.targetException
    } catch (e: ReflectiveOperationException) {
      // TODO: if sig wasn't specified we might incorrectly resolve against a method in the base
      // class
      // when a method in the superclass was intended - it'd be nice to check for that
      // TODO: also catch if there is another sig that would have worked

      // We catch and rethrow for a slightly less confusing stacktrace
      throw Exception(e.message)
    }
  }
}
