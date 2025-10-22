package com.squareup.stoic.jvmti

/**
 * Analogous to
 * https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/EventRequest.html
 */
open class EventRequest {
  var wasClosed = false

  fun close() {
    VirtualMachine.eventRequestManager.deleteEventRequest(this)
    wasClosed = true
  }
}
