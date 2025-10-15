package com.squareup.stoic.jvmti.magics

import com.squareup.stoic.jvmti.JvmtiClass
import com.squareup.stoic.jvmti.magic.MagicFields
import com.squareup.stoic.jvmti.magic.MagicMethods

val Class<*>.sm: MagicMethods
  get() {
    return MagicMethods(null, JvmtiClass[this])
  }

val Class<*>.sf: MagicFields
  get() {
    return MagicFields(null, JvmtiClass[this])
  }

val Any.m: MagicMethods
  get() {
    return MagicMethods(this, JvmtiClass[this.javaClass])
  }

val Any.f: MagicFields
  get() {
    return MagicFields(this, JvmtiClass[this.javaClass])
  }

class c {
  companion object {
    operator fun get(name: String): Class<*> {
      return Class.forName(name)
    }
  }
}

