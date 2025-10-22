package com.squareup.stoic.jvmti

/**
 * Analogous to
 * https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/EventRequestManager.html
 */
class EventRequestManager {
  // These are guarded by the `this` lock. We copy on each traversal inside the lock, and then call
  // the callbacks outside of the lock. We check each callback to make sure it wasn't closed before
  // calling it.
  //
  // TODO: wasClosed should be volatile
  // TODO: lifetimes should be tied to the plugin -
  //   when a plugin dies, its event requests should automatically unregister
  // TODO: there is too much boilerplate for each request type -
  //   find a way to share code amongst the create*/delete* implementations

  private val breakpointRequests =
    mutableMapOf<Pair<JMethodId, JLocation>, MutableList<BreakpointRequest>>()
  private val methodEntryRequests = mutableMapOf<Thread, MutableList<MethodEntryRequest>>()
  private val methodExitRequests = mutableMapOf<Thread, MutableList<MethodExitRequest>>()

  @Synchronized
  fun createBreakpointRequest(location: Location, callback: OnBreakpoint): BreakpointRequest {
    val key = Pair(location.method.methodId, location.jlocation)
    var list = breakpointRequests[key]
    if (list == null) {
      list = mutableListOf()
      breakpointRequests[key] = list
    }
    val request = BreakpointRequest(location, callback)

    if (list.size == 0) {
      VirtualMachine.nativeSetBreakpoint(location.method.methodId, location.jlocation)
    }

    list.add(request)

    return request
  }

  @Synchronized
  fun createMethodEntryRequest(thread: Thread, callback: OnMethodEntry): MethodEntryRequest {
    var list = methodEntryRequests[thread]
    if (list == null) {
      list = mutableListOf()
      methodEntryRequests[thread] = list
    }
    val request = MethodEntryRequest(thread, callback)
    if (list.size == 0) {
      VirtualMachine.nativeMethodEntryCallbacks(thread, true)
    }
    list.add(request)

    return request
  }

  @Synchronized
  fun createMethodExitRequest(thread: Thread, callback: OnMethodExit): MethodExitRequest {
    var list = methodExitRequests[thread]
    if (list == null) {
      list = mutableListOf()
      methodExitRequests[thread] = list
    }
    val request = MethodExitRequest(thread, callback)
    if (list.size == 0) {
      VirtualMachine.nativeMethodExitCallbacks(thread, true)
    }
    list.add(request)

    return request
  }

  fun deleteEventRequest(request: EventRequest) {
    when (request) {
      is BreakpointRequest -> deleteBreakpointRequest(request)
      is MethodEntryRequest -> deleteMethodEntryRequest(request)
      is MethodExitRequest -> deleteMethodExitRequest(request)
      else -> TODO()
    }
  }

  @Synchronized
  fun deleteBreakpointRequest(request: BreakpointRequest) {
    val location = request.location
    val key = Pair(location.method.methodId, location.jlocation)
    val list = breakpointRequests[key]

    // You shouldn't be able to delete a breakpoint unless you never set it before
    check(list != null)

    val sizeBefore = list.size

    list.remove(request)

    if (sizeBefore != 0 && list.size == 0) {
      VirtualMachine.nativeClearBreakpoint(location.method.methodId, location.jlocation)
    }
  }

  @Synchronized
  fun deleteMethodEntryRequest(request: MethodEntryRequest) {
    val list = methodEntryRequests[request.thread]
    check(list != null)
    val sizeBefore = list.size
    list.remove(request)
    if (sizeBefore != 0 && list.size == 0) {
      VirtualMachine.nativeMethodEntryCallbacks(request.thread, false)
    }
  }

  @Synchronized
  fun deleteMethodExitRequest(request: MethodExitRequest) {
    var list = methodExitRequests[request.thread]
    check(list != null)
    val sizeBefore = list.size
    list.remove(request)
    if (sizeBefore != 0 && list.size == 0) {
      VirtualMachine.nativeMethodExitCallbacks(request.thread, false)
    }
  }

  fun onBreakpoint(frame: StackFrame) {
    var requests: List<BreakpointRequest>
    synchronized(this) {
      val location = frame.location
      val key = Pair(location.method.methodId, location.jlocation)
      // This should always be non-null because we should only be getting callbacks for requests we
      // set previously. Due to race conditions it might be empty
      requests = breakpointRequests[key]!!.toList()
    }

    for (request in requests) {
      if (!request.wasClosed) {
        request.callback(frame)
      }
    }
  }

  fun onMethodEntry(frame: StackFrame) {
    var requests: List<MethodEntryRequest>
    synchronized(this) {
      // This should always be non-null because we should only be getting callbacks for requests we
      // set previously.
      requests = methodEntryRequests[Thread.currentThread()]!!.toList()
    }

    for (request in requests) {
      if (!request.wasClosed) {
        request.callback(frame)
      }
    }
  }

  fun onMethodExit(frame: StackFrame, value: Any?, wasPoppedByException: Boolean) {
    var requests: List<MethodExitRequest>
    synchronized(this) {
      // This should always be non-null because we should only be getting callbacks for requests we
      // set previously.
      requests = methodExitRequests[Thread.currentThread()]!!.toList()
    }

    for (request in requests) {
      if (!request.wasClosed) {
        request.callback(frame, value, wasPoppedByException)
      }
    }
  }
}
