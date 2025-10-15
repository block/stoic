package com.squareup.stoic.jvmti

typealias OnMethodExit = (frame: StackFrame, value: Any?, wasPoppedByException: Boolean) -> Unit

class MethodExitRequest(val thread: Thread, val callback: OnMethodExit): EventRequest() {
}