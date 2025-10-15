package com.squareup.stoic.jvmti

/**
 * Analog of https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Locatable.html
 */
interface Locatable {
  fun location(): Location
}