package com.squareup.stoic.jvmti

typealias OnBreakpoint = (frame: StackFrame) -> Unit

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/EventRequest.html
 */
class BreakpointRequest(val location: Location, val callback: OnBreakpoint) : EventRequest() {
}