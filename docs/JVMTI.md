# JVMTI

The com.squareup.stoic.jvmti package implements something approximating JDI,
but instead of being implemented on JDWP it is implemented directly on top of
JVMTI.

It is hoped by using the structure of JDI I avoid running into any design landmines. Since we are
running in-process, many aspects of JDI can be simplified, so I didn't implement JDI interfaces
directly.

## JVMTI/JDI notes
Unlike JDI, there are no mirrors - i.e. https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/class-use/Mirror.html
and https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Value.html - Since we are
running in-process, we can return values directly. This is simpler.

Raw JVMTI functionality is exposed as static JNI functions on com.square.stoic.jvmti.VirtualMachine.
`nativeCallback*` methods are JVMTI callbacks invoked from native. All other `native*` methods are
JVMTI APIs, with some differences:
1. Instead of exposing tagging to Kotlin/Java, I've instead of opted to expose higher level APIs
   built on top of tagging - e.g. nativeInstances (note: there are currently race conditions here
   - we should acquire a lock while tagging since the tags are global)
2. Many JVMTI APIs take a `depth` parameter - the count of frames between the current frame of the
   thread and the frame we wish to access. This doesn't make sense when calling APIs from
   Kotlin/Java because the depth of frame is not stable. Instead, we use `height` - the count of
   frames from the bottom of the stack to frame we wish to access. The implementation computes the
   depth on-demand and uses this when calling JVMTI. e.g. `nativeGetLocalObject`
3. Callbacks are invoked on the thread that generates them. 
4. Since everything is in-process, there can only be one VirtualMachine, so its a singleton.

## JVMTI/JPDA links
https://www.pnfsoftware.com/blog/debugging-android-apps-on-android-pie-and-above/
https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/jdwp-spec.html
https://docs.oracle.com/javase/8/docs/platform/jpda/jdwp/jdwp-protocol.html

## JDWP implementation on top of JVMTI. e.g. setBreakpoint:
https://cs.android.com/android/platform/superproject/main/+/d2f87bde534633e17d5c45f908094d5af66ed7e8:external/oj-libjdwp/src/share/back/eventFilter.c;l=1020

https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/

## JDI implementation on top of JDWP
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/classes/com/sun/tools/jdi/
https://cs.android.com/android/platform/superproject/main/+/main:out/soong/.intermediates/external/oj-libjdwp/jdwp_generated_java/gen/JDWP.java

### e.g. How JDI implements BreakpointRequest.addInstanceFilter
https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/BreakpointRequest.html#addInstanceFilter-com.sun.jdi.ObjectReference-
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/classes/com/sun/tools/jdi/EventRequestManagerImpl.java;l=304

### How JDWP implements it
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/eventFilter.c;l=465
(eventFilterRestricted_passesFilter is called from event_callback which in turn is called by
cbBreakpoint and other actual JVMTI callbacks)
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/eventHandler.c;l=656
