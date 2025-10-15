package com.squareup.stoic.jvmti

// No error has occurred. This is the error code that is returned on successful completion of the function.
const val JVMTI_ERROR_NONE = 0

// Pointer is unexpectedly NULL.
const val JVMTI_ERROR_NULL_POINTER = 100

// The function attempted to allocate memory and no more memory was available for allocation.
const val JVMTI_ERROR_OUT_OF_MEMORY = 110

// The desired functionality has not been enabled in this virtual machine.
const val JVMTI_ERROR_ACCESS_DENIED = 111

// The thread being used to call this function is not attached to the virtual machine. Calls must be made from attached threads. See AttachCurrentThread in the JNI invocation API.
const val JVMTI_ERROR_UNATTACHED_THREAD = 115

// The JVM TI environment provided is no longer connected or is not an environment.
const val JVMTI_ERROR_INVALID_ENVIRONMENT = 116

// The desired functionality is not available in the current phase. Always returned if the virtual machine has completed running.
const val JVMTI_ERROR_WRONG_PHASE = 112

// An unexpected internal error has occurred.
const val JVMTI_ERROR_INTERNAL = 113

//
// Function Specific Required Errors
// The following errors are returned by some JVM TI functions and must be returned by the implementation when the condition occurs.
//

// Invalid priority.
const val JVMTI_ERROR_INVALID_PRIORITY = 12

// Thread was not suspended.
const val JVMTI_ERROR_THREAD_NOT_SUSPENDED = 13

// Thread already suspended.
const val JVMTI_ERROR_THREAD_SUSPENDED = 14

// This operation requires the thread to be alive--that is, it must be started and not yet have died.
const val JVMTI_ERROR_THREAD_NOT_ALIVE = 15

// The class has been loaded but not yet prepared.
const val JVMTI_ERROR_CLASS_NOT_PREPARED = 22

// There are no Java programming language or JNI stack frames at the specified depth.
const val JVMTI_ERROR_NO_MORE_FRAMES = 31

// Information about the frame is not available (e.g. for native frames).
const val JVMTI_ERROR_OPAQUE_FRAME = 32

// Item already set.
const val JVMTI_ERROR_DUPLICATE = 40

// Desired element (e.g. field or breakpoint) not found
const val JVMTI_ERROR_NOT_FOUND = 41

// This thread doesn't own the raw monitor.
const val JVMTI_ERROR_NOT_MONITOR_OWNER = 51

// The call has been interrupted before completion.
const val JVMTI_ERROR_INTERRUPT = 52

// The class cannot be modified.
const val JVMTI_ERROR_UNMODIFIABLE_CLASS = 79

// The functionality is not available in this virtual machine.
const val JVMTI_ERROR_NOT_AVAILABLE = 98

// The requested information is not available.
const val JVMTI_ERROR_ABSENT_INFORMATION = 101

// The specified event type ID is not recognized.
const val JVMTI_ERROR_INVALID_EVENT_TYPE = 102

// The requested information is not available for native method.
const val JVMTI_ERROR_NATIVE_METHOD = 104

// The class loader does not support this operation.
const val JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED = 106

//
// Function Specific Agent Errors
// The following errors are returned by some JVM TI functions. They are returned in the event of invalid parameters passed by the agent or usage in an invalid context. An implementation is not required to detect these errors.
//

// The passed thread is not a valid thread.
const val JVMTI_ERROR_INVALID_THREAD = 10

// Invalid field.
const val JVMTI_ERROR_INVALID_FIELDID = 25

// Invalid method.
const val JVMTI_ERROR_INVALID_METHODID = 23

// Invalid location.
const val JVMTI_ERROR_INVALID_LOCATION = 24

// Invalid object.
const val JVMTI_ERROR_INVALID_OBJECT = 20

// Invalid class.
const val JVMTI_ERROR_INVALID_CLASS = 21

// The variable is not an appropriate type for the function used.
const val JVMTI_ERROR_TYPE_MISMATCH = 34

// Invalid slot.
const val JVMTI_ERROR_INVALID_SLOT = 35

// The capability being used is false in this environment.
const val JVMTI_ERROR_MUST_POSSESS_CAPABILITY = 99

// Thread group invalid.
const val JVMTI_ERROR_INVALID_THREAD_GROUP = 11

// Invalid raw monitor.
const val JVMTI_ERROR_INVALID_MONITOR = 50

// Illegal argument.
const val JVMTI_ERROR_ILLEGAL_ARGUMENT = 103

// The state of the thread has been modified, and is now inconsistent.
const val JVMTI_ERROR_INVALID_TYPESTATE = 65

// A new class file has a version number not supported by this VM.
const val JVMTI_ERROR_UNSUPPORTED_VERSION = 68

// A new class file is malformed (the VM would return a ClassFormatError).
const val JVMTI_ERROR_INVALID_CLASS_FORMAT = 60

// The new class file definitions would lead to a circular definition (the VM would return a ClassCircularityError).
const val JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION = 61

// A new class file would require adding a method.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED = 63

// A new class version changes a field.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED = 64

// The class bytes fail verification.
const val JVMTI_ERROR_FAILS_VERIFICATION = 62

// A direct superclass is different for the new class version, or the set of directly implemented interfaces is different.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED = 66

// A new class version does not declare a method declared in the old class version.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED = 67

// The class name defined in the new class file is different from the name in the old class object.
const val JVMTI_ERROR_NAMES_DONT_MATCH = 69

// A new class version has different modifiers.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED = 70

// A method in the new class version has different modifiers than its counterpart in the old class version.
const val JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED = 71

class JvmtiException(val errorCode: Int, desc: String) : Exception("$desc: $errorCode")