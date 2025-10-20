#include <cstddef>
#include <fcntl.h>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <unistd.h>
#include <unordered_set>

#define LOG_TAG "stoic"

#include <android/log.h>
#include "stoic_logging.h"
#include "scoped_local_ref.h"
#include "scoped_utf_chars.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "jvmti.h"

#define CHECK_JVMTI(x) CHECK_EQ((x), JVMTI_ERROR_NONE)

#define JVMTI_THROW_IF_ERROR(x, if_fail) do { int result = (x); if (result != JVMTI_ERROR_NONE) { throwJvmtiError(jni, result, #x); if_fail; } } while (false)

// Note: Art jvmti capabilities are documented here:
// https://android.googlesource.com/platform/art/+/refs/heads/main/openjdkjvmti/art_jvmti.h#256

//using namespace std;
//
typedef struct {
  jvmtiEnv *jvmti;
  jclass stoicJvmtiVmClass;
 
  // callbacks
  jmethodID nativeCallbackOnBreakpoint;
  jmethodID nativeCallbackOnMethodEntry;
  jmethodID nativeCallbackOnMethodExit;
 
  // boxing stuff
  jclass booleanClass;
  jclass byteClass;
  jclass characterClass;
  jclass shortClass;
  jclass integerClass;
  jclass longClass;
  jclass floatClass;
  jclass doubleClass;
  jmethodID booleanCtor;
  jmethodID byteCtor;
  jmethodID characterCtor;
  jmethodID shortCtor;
  jmethodID integerCtor;
  jmethodID longCtor;
  jmethodID floatCtor;
  jmethodID doubleCtor;
 
  // com.squareup.stoic.jvmti.JvmtiMethod stuff
  jclass stoicJvmtiMethodClass;
  jmethodID stoicJvmtiMethodCtor;
  jfieldID stoicJvmtiMethodMethodId;
  jfieldID stoicJvmtiMethodPrivateClazz;
  jfieldID stoicJvmtiMethodPrivateName;
  jfieldID stoicJvmtiMethodPrivateSignature;
  jfieldID stoicJvmtiMethodPrivateGeneric;
  jfieldID stoicJvmtiMethodPrivateStartLocation;
  jfieldID stoicJvmtiMethodPrivateEndLocation;
  jfieldID stoicJvmtiMethodPrivateArgsSize;
  jfieldID stoicJvmtiMethodPrivateMaxLocals;
  jfieldID stoicJvmtiMethodPrivateModifiers;

  // com.squareup.stoic.jvmti.JvmtiField stuff
  jclass stoicJvmtiFieldClass;
  jmethodID stoicJvmtiFieldCtor;
  jfieldID stoicJvmtiFieldClazz;
  jfieldID stoicJvmtiFieldFieldId;
  jfieldID stoicJvmtiFieldPrivateName;
  jfieldID stoicJvmtiFieldPrivateSignature;
  jfieldID stoicJvmtiFieldPrivateGeneric;
  jfieldID stoicJvmtiFieldPrivateModifiers;
} GlobalAgentData;
 
static GlobalAgentData *gdata;

// We don't want to process a callback for our callback to the VM
// TODO: We could probably be more efficient with SetEventNotificationMode
// TODO: We might want to allow the callback to ask for callbacks to be
// temporarily re-enabled
thread_local bool callbacksAllowed = true;

static void
throwJvmtiError(JNIEnv* jni, int result, const char* desc) {
  ScopedLocalRef<jclass> jvmtiExceptionClass(jni, jni->FindClass("com/squareup/stoic/jvmti/JvmtiException"));
  CHECK(jvmtiExceptionClass.get() != NULL);
  jmethodID ctor = jni->GetMethodID(jvmtiExceptionClass.get(), "<init>", "(ILjava/lang/String;)V");
  CHECK(ctor != NULL);
  ScopedLocalRef<jstring> jdesc(jni, jni->NewStringUTF(desc));
  ScopedLocalRef<jthrowable> exc(jni, (jthrowable) jni->NewObject(jvmtiExceptionClass.get(), ctor, result, jdesc.get()));
  CHECK(exc.get() != NULL);
  jni->Throw(exc.get());
}

// Helper to ensure a specific capability is enabled, adding it on-demand if not already present
// Returns JVMTI_ERROR_NONE if the capability is available (or was successfully added)
// Returns the actual error code from GetCapabilities or AddCapabilities otherwise
static jvmtiError
ensureCapability(jvmtiEnv* jvmti, jvmtiCapabilities* cap) {
  // First check if we already have the capability
  jvmtiCapabilities current_caps;
  memset(&current_caps, 0, sizeof(jvmtiCapabilities));
  jvmtiError err = jvmti->GetCapabilities(&current_caps);
  if (err != JVMTI_ERROR_NONE) {
    return err;
  }

  // Check if we already have this capability by comparing the structs
  // We'll use the fact that capabilities are boolean fields in the struct
  unsigned char* current_ptr = (unsigned char*)&current_caps;
  unsigned char* requested_ptr = (unsigned char*)cap;
  bool already_have = true;
  for (size_t i = 0; i < sizeof(jvmtiCapabilities); i++) {
    if (requested_ptr[i] && !current_ptr[i]) {
      already_have = false;
      break;
    }
  }

  if (already_have) {
    return JVMTI_ERROR_NONE;
  }

  // Try to add the capability
  return jvmti->AddCapabilities(cap);
}
 
jvmtiIterationControl JNICALL
jvmtiHeapObjectCallback_tagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) {
  *tag_ptr = 1;
  return JVMTI_ITERATION_CONTINUE;
}

jvmtiIterationControl JNICALL
jvmtiHeapObjectCallback_untagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) {
  *tag_ptr = 0;
  return JVMTI_ITERATION_CONTINUE;
}

jint JNICALL
jvmtiHeapIterationCallback_tagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, jint length, void* user_data) {
  *tag_ptr = *static_cast<jlong*>(user_data);
  return 0;
}

jint JNICALL
jvmtiHeapIterationCallback_untagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, jint length, void* user_data) {
  *tag_ptr = 0;
  return 0;
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeInstances(JNIEnv *jni, jobject vmClass, jclass klass, jboolean includeSubclasses) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Clear all tags in the heap
  {
    jvmtiHeapCallbacks callbacks = {
      .heap_iteration_callback = jvmtiHeapIterationCallback_untagUnconditionally,
    };

    CHECK_JVMTI(jvmti->IterateThroughHeap(
          JVMTI_HEAP_FILTER_UNTAGGED,
          nullptr,
          &callbacks,
          nullptr));
  }

  if (includeSubclasses) {
    // klassClass = java.lang.Class
    ScopedLocalRef<jclass> klassClass(jni, jni->FindClass("java/lang/Class"));
    if (!klassClass.get()) {
        jni->ExceptionDescribe();
        jni->ExceptionClear();
        return nullptr;
    }

    // Tag every Class object
    jlong tag = 1;
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klassClass.get(),
          JVMTI_HEAP_OBJECT_EITHER,
          jvmtiHeapObjectCallback_tagUnconditionally,
          &tag));

    // Iterate through every Class object, clearing the tags of classes that aren't subclasses of klass
    jobject* obj_list;
    jint obj_len;
    jlong tagOne = 1;
    CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tagOne, &obj_len, &obj_list, nullptr));
    jmethodID method_isAssignableFrom = jni->GetMethodID(klassClass.get(), "isAssignableFrom", "(Ljava/lang/Class;)Z");
    CHECK(method_isAssignableFrom != nullptr);
    for (int i = 0; i < obj_len; i++) {
      ScopedLocalRef<jobject> klassCandidate(jni, obj_list[i]);

      // Never throws (famous last words?)
      jboolean isAssignable = jni->CallBooleanMethod(klass, method_isAssignableFrom, klassCandidate.get());
      
      if (!isAssignable) {
        jlong tagZero = 0;
        CHECK_JVMTI(jvmti->SetTag(klassCandidate.get(), tagZero));
      }
    }

    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) obj_list));
    obj_list = NULL;

    // Tag every object whose class is tagged
    {
      jvmtiHeapCallbacks callbacks = {
        .heap_iteration_callback = jvmtiHeapIterationCallback_tagUnconditionally,
      };
      CHECK_JVMTI(jvmti->IterateThroughHeap(
          JVMTI_HEAP_FILTER_CLASS_UNTAGGED,
          nullptr,
          &callbacks,
          &tagOne));
    }

    // TODO: This wouldn't work for subclasses of java.lang.Class
    // java.lang.Class is final so there aren't any, but if people
    // asked for instances of java.lang.Class and subclasses, this
    // will give the wrong answer. So I need to special-case that.

    // Untag instances of java.lang.Class
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klassClass.get(),
          JVMTI_HEAP_OBJECT_TAGGED,
          jvmtiHeapObjectCallback_untagUnconditionally,
          nullptr));
  } else {
    // tag every object whose Class is exactly klass
    jlong tag = 1;
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klass,
          JVMTI_HEAP_OBJECT_EITHER,
          jvmtiHeapObjectCallback_tagUnconditionally,
          &tag));
  }
 
  // Get all of the tagged objects
  jobject* obj_list;
  jint obj_len;
  jlong tag = 1;
  CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tag, &obj_len, &obj_list, nullptr));
 
  jobjectArray klassArray = jni->NewObjectArray(obj_len, klass, NULL);
  if (klassArray == nullptr) {
    LOG(ERROR) << "NewObjectArray failed";
    return nullptr;
  }
 
  for (int i = 0; i < obj_len; i++) {
    ScopedLocalRef<jobject> obj(jni, obj_list[i]);
    jni->SetObjectArrayElement(klassArray, i, obj.get());
  }

  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) obj_list));
  obj_list = NULL;

  return klassArray;
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeSubclasses(JNIEnv *jni, jobject vmClass, jclass klass) {
  jvmtiEnv* jvmti = gdata->jvmti;
  // TODO: verify VirtualMachine.class lock held

  // Clear all tags in the heap
  {
    jvmtiHeapCallbacks callbacks = {
      .heap_iteration_callback = jvmtiHeapIterationCallback_untagUnconditionally,
    };

    CHECK_JVMTI(jvmti->IterateThroughHeap(
          JVMTI_HEAP_FILTER_UNTAGGED,
          nullptr,
          &callbacks,
          nullptr));
  }

  // klassClass = java.lang.Class
  ScopedLocalRef<jclass> klassClass(jni, jni->FindClass("java/lang/Class"));
  if (!klassClass.get()) {
      jni->ExceptionDescribe();
      jni->ExceptionClear();
      return nullptr;
  }

  // Tag every Class object
  jlong tag = 1;
  CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
        klassClass.get(),
        JVMTI_HEAP_OBJECT_EITHER,
        jvmtiHeapObjectCallback_tagUnconditionally,
        &tag));

  // Iterate through every Class object, clearing the tags of classes that aren't subclasses of klass
  jobject* obj_list = NULL;
  jint obj_len = -1;
  const jlong tagOne = 1;
  CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tagOne, &obj_len, &obj_list, nullptr));
  jmethodID method_isAssignableFrom = jni->GetMethodID(klassClass.get(), "isAssignableFrom", "(Ljava/lang/Class;)Z");
  CHECK(method_isAssignableFrom != nullptr);
  for (int i = 0; i < obj_len; i++) {
    ScopedLocalRef<jobject> klassCandidate(jni, obj_list[i]);

    // Never throws (famous last words?)
    jboolean isAssignable = jni->CallBooleanMethod(klass, method_isAssignableFrom, klassCandidate.get());
    
    if (!isAssignable) {
      jlong tagZero = 0;
      CHECK_JVMTI(jvmti->SetTag(klassCandidate.get(), tagZero));
    }
  }

  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) obj_list));
  obj_list = NULL;

  // Get all of the objects tagged with 1
  obj_len = -1;
  CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tagOne, &obj_len, &obj_list, nullptr));
 
  jobjectArray klassArray = jni->NewObjectArray(obj_len, klassClass.get(), NULL);
  if (klassArray == nullptr) {
    LOG(ERROR) << "NewObjectArray failed";
    return nullptr;
  }
 
  for (int i = 0; i < obj_len; i++) {
    ScopedLocalRef<jobject> obj(jni, obj_list[i]);
    jni->SetObjectArrayElement(klassArray, i, obj.get());
  }

  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) obj_list));
  obj_list = NULL;

  return klassArray;
}

JNIEXPORT jlong JNICALL
Jvmti_VirtualMachine_nativeGetMethodId(JNIEnv *jni, jobject vmClass, jclass clazz, jstring methodName, jstring methodSignature) {
  const char* methodNameChars = jni->GetStringUTFChars(methodName, NULL);
  const char* methodSignatureChars = jni->GetStringUTFChars(methodSignature, NULL);
  jmethodID methodId = jni->GetMethodID(clazz, methodNameChars, methodSignatureChars);
  jni->ReleaseStringUTFChars(methodName, methodNameChars);
  jni->ReleaseStringUTFChars(methodSignature, methodSignatureChars);

  return (jlong) methodId;
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeSetBreakpoint(JNIEnv *jni, jobject vmClass, jlong methodId, jlong location) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  JVMTI_THROW_IF_ERROR(jvmti->SetBreakpoint(castMethodId, location), ;);
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeClearBreakpoint(JNIEnv *jni, jobject vmClass, jlong methodId, jlong location) {
  jvmtiEnv* jvmti = gdata->jvmti;
  CHECK_JVMTI(jvmti->ClearBreakpoint((jmethodID) methodId, (jlocation) location));
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeGetMethodCoreMetadata(JNIEnv *jni, jobject vmClass, jobject jvmtiMethod) {
  jvmtiEnv* jvmti = gdata->jvmti;

  jlong longMethodId = jni->GetLongField(jvmtiMethod, gdata->stoicJvmtiMethodMethodId);
  jmethodID castMethodId = reinterpret_cast<jmethodID>(longMethodId);

  char* name = NULL;
  char* signature = NULL;
  char* generic = NULL;
  CHECK_JVMTI(jvmti->GetMethodName(castMethodId, &name, &signature, &generic));
  ScopedLocalRef<jstring> jname(jni, jni->NewStringUTF(name));
  ScopedLocalRef<jstring> jsignature(jni, jni->NewStringUTF(signature));
  ScopedLocalRef<jstring> jgeneric(jni, jni->NewStringUTF(generic));

  jlocation startLocation = -1;
  jlocation endLocation = -1;

  jvmtiError error = JVMTI_ERROR_NONE;
  error = jvmti->GetMethodLocation(castMethodId, &startLocation, &endLocation);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jint argsSize = -1;
  error = jvmti->GetArgumentsSize(castMethodId, &argsSize);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jint maxLocals = -1;
  error = jvmti->GetMaxLocals(castMethodId, &maxLocals);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jint modifiers = -1;
  CHECK_JVMTI(jvmti->GetMethodModifiers(castMethodId, &modifiers));

  jclass declaringClass = nullptr;
  CHECK_JVMTI(jvmti->GetMethodDeclaringClass(castMethodId, &declaringClass));
  jni->SetObjectField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateClazz, declaringClass);
  jni->DeleteLocalRef(declaringClass);
  declaringClass = nullptr;

  jni->SetObjectField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateName, jname.get());
  jni->SetObjectField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateSignature, jsignature.get());
  jni->SetObjectField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateGeneric, jgeneric.get());
  jni->SetLongField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateStartLocation, startLocation);
  jni->SetLongField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateEndLocation, endLocation);
  jni->SetIntField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateArgsSize, argsSize);
  jni->SetIntField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateMaxLocals, maxLocals);
  jni->SetIntField(jvmtiMethod, gdata->stoicJvmtiMethodPrivateModifiers, modifiers);

  jvmti->Deallocate((unsigned char*) name);
  jvmti->Deallocate((unsigned char*) signature);
  jvmti->Deallocate((unsigned char*) generic);
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeGetFieldCoreMetadata(JNIEnv *jni, jobject vmClass, jobject jvmtiField) {
  jvmtiEnv* jvmti = gdata->jvmti;

  jlong longFieldId = jni->GetLongField(jvmtiField, gdata->stoicJvmtiFieldFieldId);
  jfieldID castFieldId = reinterpret_cast<jfieldID>(longFieldId);

  ScopedLocalRef<jclass> clazz(jni, (jclass) jni->GetObjectField(jvmtiField, gdata->stoicJvmtiFieldClazz));

  char* name = NULL;
  char* signature = NULL;
  char* generic = NULL;
  CHECK_JVMTI(jvmti->GetFieldName(clazz.get(), castFieldId, &name, &signature, &generic));
  ScopedLocalRef<jstring> jname(jni, jni->NewStringUTF(name));
  ScopedLocalRef<jstring> jsignature(jni, jni->NewStringUTF(signature));
  ScopedLocalRef<jstring> jgeneric(jni, jni->NewStringUTF(generic));

  jint modifiers = -1;
  CHECK_JVMTI(jvmti->GetFieldModifiers(clazz.get(), castFieldId, &modifiers));

  jni->SetObjectField(jvmtiField, gdata->stoicJvmtiFieldPrivateName, jname.get());
  jni->SetObjectField(jvmtiField, gdata->stoicJvmtiFieldPrivateSignature, jsignature.get());
  jni->SetObjectField(jvmtiField, gdata->stoicJvmtiFieldPrivateGeneric, jgeneric.get());
  jni->SetIntField(jvmtiField, gdata->stoicJvmtiFieldPrivateModifiers, modifiers);

  jvmti->Deallocate((unsigned char*) name);
  jvmti->Deallocate((unsigned char*) signature);
  jvmti->Deallocate((unsigned char*) generic);
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetLocalVariables(JNIEnv *jni, jobject vmClass, jlong methodId) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  jint entryCount = -1;
  jvmtiLocalVariableEntry* table = NULL;

  // Ensure we have the capability to access local variables (needed for GetLocalVariableTable)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return NULL;
  }

  // This can fail if local variable information isn't available
  JVMTI_THROW_IF_ERROR(jvmti->GetLocalVariableTable(castMethodId, &entryCount, &table), return NULL);

  ScopedLocalRef<jclass> LocalVariable(jni, jni->FindClass("com/squareup/stoic/jvmti/LocalVariable"));
  CHECK(LocalVariable.get() != NULL);
  jmethodID ctor = jni->GetMethodID(LocalVariable.get(), "<init>", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
  CHECK(ctor != NULL);
  ScopedLocalRef<jobjectArray> jTable(jni, jni->NewObjectArray(entryCount, LocalVariable.get(), NULL));
  CHECK(jTable.get() != NULL);

  for (int i = 0; i < entryCount; i++) {
    jlong startLocation = table[i].start_location;
    jint length = table[i].length;
    ScopedLocalRef<jstring> name(jni, jni->NewStringUTF(table[i].name));
    ScopedLocalRef<jstring> signature(jni, jni->NewStringUTF(table[i].signature));
    ScopedLocalRef<jstring> genericSignature(jni, jni->NewStringUTF(table[i].generic_signature));
    jint slot = table[i].slot;
    ScopedLocalRef<jobject> localVar(jni, jni->NewObject(LocalVariable.get(), ctor, startLocation, length, name.get(), signature.get(), genericSignature.get(), slot));
    CHECK(localVar.get() != NULL);
    jni->SetObjectArrayElement(jTable.get(), i, localVar.get());
  }

  // Deallocate
  for (int i = 0; i < entryCount; i++) {
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].name));
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].signature));
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].generic_signature));
    table[i].name = table[i].signature = table[i].generic_signature = NULL;
  }
  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table));
  table = NULL;

  return jTable.release();
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetLocalObject(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Ensure we have the capability to access local variables (needed for GetLocalObject)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return NULL;
  }

  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jobject result = NULL;
  CHECK_JVMTI(jvmti->GetLocalObject(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jint JNICALL
Jvmti_VirtualMachine_nativeGetLocalInt(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Ensure we have the capability to access local variables (needed for GetLocalInt)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return 0;
  }

  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jint result = 0;
  CHECK_JVMTI(jvmti->GetLocalInt(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jlong JNICALL
Jvmti_VirtualMachine_nativeGetLocalLong(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Ensure we have the capability to access local variables (needed for GetLocalLong)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return 0;
  }

  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jlong result = 0;
  CHECK_JVMTI(jvmti->GetLocalLong(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jfloat JNICALL
Jvmti_VirtualMachine_nativeGetLocalFloat(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Ensure we have the capability to access local variables (needed for GetLocalFloat)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return 0.0f;
  }

  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jfloat result = 0.0f;
  CHECK_JVMTI(jvmti->GetLocalFloat(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jdouble JNICALL
Jvmti_VirtualMachine_nativeGetLocalDouble(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Ensure we have the capability to access local variables (needed for GetLocalDouble)
  jvmtiCapabilities caps = {0};
  caps.can_access_local_variables = JNI_TRUE;
  jvmtiError capErr = ensureCapability(jvmti, &caps);
  if (capErr != JVMTI_ERROR_NONE) {
    throwJvmtiError(jni, capErr, "jvmti->AddCapabilities(can_access_local_variables)");
    return 0.0;
  }

  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jdouble result = 0.0;
  CHECK_JVMTI(jvmti->GetLocalDouble(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetClassMethods(JNIEnv *jni, jobject vmClass, jclass clazz) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint methodCount = -1;
  jmethodID* methods = NULL;
  CHECK_JVMTI(jvmti->GetClassMethods(clazz, &methodCount, &methods));

  ScopedLocalRef<jobjectArray> jmethods(jni, jni->NewObjectArray(methodCount, gdata->stoicJvmtiMethodClass, NULL));
  CHECK(jmethods.get() != NULL);
  for (int i = 0; i < methodCount; i++) {
    jlong longMethodId = reinterpret_cast<jlong>(methods[i]);
    ScopedLocalRef<jobject> method(jni, jni->NewObject(
          gdata->stoicJvmtiMethodClass,
          gdata->stoicJvmtiMethodCtor,
          longMethodId));

    jni->SetObjectArrayElement(jmethods.get(), i, method.get());
  }

  jvmti->Deallocate((unsigned char*) methods);

  return jmethods.release();
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetClassFields(JNIEnv *jni, jobject vmClass, jclass clazz) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint fieldCount = -1;
  jfieldID* fields = NULL;
  CHECK_JVMTI(jvmti->GetClassFields(clazz, &fieldCount, &fields));

  ScopedLocalRef<jobjectArray> jfields(jni, jni->NewObjectArray(fieldCount, gdata->stoicJvmtiFieldClass, NULL));
  CHECK(jfields.get() != NULL);
  for (int i = 0; i < fieldCount; i++) {
    jlong longFieldId = reinterpret_cast<jlong>(fields[i]);
    ScopedLocalRef<jobject> field(jni, jni->NewObject(
          gdata->stoicJvmtiFieldClass,
          gdata->stoicJvmtiFieldCtor,
          clazz,
          longFieldId));

    jni->SetObjectArrayElement(jfields.get(), i, field.get());
  }

  jvmti->Deallocate((unsigned char*) fields);

  return jfields.release();
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeToReflectedField(JNIEnv *jni, jobject vmClass, jclass clazz, jlong fieldId, jboolean isStatic) {
  jfieldID castFieldId = reinterpret_cast<jfieldID>(fieldId);
  return jni->ToReflectedField(clazz, castFieldId, isStatic);
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeToReflectedMethod(JNIEnv *jni, jobject vmClass, jclass clazz, jlong methodId, jboolean isStatic) {
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  return jni->ToReflectedMethod(clazz, castMethodId, isStatic);
}

JNIEXPORT jlong JNICALL
Jvmti_VirtualMachine_nativeFromReflectedMethod(JNIEnv *jni, jobject vmClass, jobject method) {
  jmethodID methodId = jni->FromReflectedMethod(method);
  return reinterpret_cast<jlong>(methodId);
}

JNIEXPORT jstring JNICALL
Jvmti_VirtualMachine_nativeGetClassSignature(JNIEnv *jni, jobject vmClass, jclass clazz) {
  char* signature = NULL;
  char* generic = NULL;
  CHECK_JVMTI(gdata->jvmti->GetClassSignature(clazz, &signature, &generic));
  ScopedLocalRef<jstring> jsignature(jni, jni->NewStringUTF(signature));
  //ScopedLocalRef<jstring> jgeneric(jni, jni->NewStringUTF(generic));
  gdata->jvmti->Deallocate((unsigned char*) signature);
  gdata->jvmti->Deallocate((unsigned char*) generic);

  return jsignature.release();
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeMethodEntryCallbacks(JNIEnv *jni, jobject vmClass, jthread thread, jboolean isEnabled) {
  // This requires the can_generate_method_entry_events capability, which must be added at load time.
  JVMTI_THROW_IF_ERROR(gdata->jvmti->SetEventNotificationMode(isEnabled ? JVMTI_ENABLE : JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, thread), return);
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeMethodExitCallbacks(JNIEnv *jni, jobject vmClass, jthread thread, jboolean isEnabled) {
  // This requires the can_generate_method_exit_events capability, which must be added at load time.
  JVMTI_THROW_IF_ERROR(gdata->jvmti->SetEventNotificationMode(isEnabled ? JVMTI_ENABLE : JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread), return);
}

struct AgentInfo {
  std::string options;
};

// Converts a class name to a type descriptor
// (ex. "java.lang.String" to "Ljava/lang/String;")
std::string classNameToDescriptor(const char* className) {
  std::stringstream ss;
  ss << "L";
  for (auto p = className; *p != '\0'; ++p) {
    ss << (*p == '.' ? '/' : *p);
  }
  ss << ";";
  return ss.str();
}

// Converts a descriptor (Lthis/style/of/name;) to a jni-FindClass style Fully-qualified class name
// (this/style/of/name).
std::string DescriptorToFQCN(const std::string& descriptor) {
  return descriptor.substr(1, descriptor.size() - 2);
}

static AgentInfo* GetAgentInfo(jvmtiEnv* jvmti) {
  AgentInfo* ai = nullptr;
  CHECK_EQ(jvmti->GetEnvironmentLocalStorage(reinterpret_cast<void**>(&ai)), JVMTI_ERROR_NONE);
  CHECK(ai != nullptr);
  return ai;
}

static void JNICALL
CbBreakpoint(jvmtiEnv* jvmti, JNIEnv* jni, jthread thread, jmethodID methodId, jlocation location) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnBreakpoint,
      methodIdAsLong,
      location,
      count);
  callbacksAllowed = true;
}

static void JNICALL
CbMethodEntry(
    jvmtiEnv* jvmti,
    JNIEnv* jni,
    jthread thread,
    jmethodID methodId) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  // Note: The methodId parameter tells us which method was entered, but this may not
  // match the top stack frame (frame 0) when inspected via GetFrameLocation.
  // This occurs for native methods, especially intrinsics like System.arraycopy:
  // - JVMTI fires MethodEntry for the native method (e.g., System.arraycopy)
  // - But GetFrameLocation returns the calling Java method (e.g., Arrays.copyOf)
  // - Native methods may not appear as distinct stack frames, particularly when
  //   inlined/intrinsified by the JVM
  // We trust the methodId parameter from JVMTI rather than attempting to verify it
  // against the stack, as the stack may not reflect native method calls as separate frames.
  jmethodID frameMethodId = nullptr;
  jlocation location = -1;
  CHECK_JVMTI(jvmti->GetFrameLocation(thread, 0, &frameMethodId, &location));

  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnMethodEntry,
      methodIdAsLong,
      location,
      count);
  callbacksAllowed = true;
}

static jobject
Box(JNIEnv* jni, char type, jvalue value) {
  switch (type) {
    case 'Z': return jni->NewObject(gdata->booleanClass, gdata->booleanCtor, value.z);
    case 'B': return jni->NewObject(gdata->byteClass, gdata->byteCtor, value.b);
    case 'C': return jni->NewObject(gdata->characterClass, gdata->characterCtor, value.c);
    case 'S': return jni->NewObject(gdata->shortClass, gdata->shortCtor, value.s);
    case 'I': return jni->NewObject(gdata->integerClass, gdata->integerCtor, value.i);
    case 'J': return jni->NewObject(gdata->longClass, gdata->longCtor, value.j);
    case 'F': return jni->NewObject(gdata->floatClass, gdata->floatCtor, value.f);
    case 'D': return jni->NewObject(gdata->doubleClass, gdata->doubleCtor, value.d);

    case 'L':
    case '[':
      // NewLocalRef so we can clean it up, just like the others
      return jni->NewLocalRef(value.l);

    case 'V': return nullptr;

    default:
      __android_log_print(ANDROID_LOG_ERROR, "stoic", "unhandled return type: %c\n", type);
      return NULL;
  }
}

static void JNICALL
CbMethodExit(
    jvmtiEnv* jvmti,
    JNIEnv* jni,
    jthread thread,
    jmethodID methodId,
    jboolean was_popped_by_exception,
    jvalue return_value) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  // Note: The methodId parameter tells us which method was exited, but this may not
  // match the top stack frame (frame 0) when inspected via GetFrameLocation.
  // This occurs for native methods, especially intrinsics like System.arraycopy:
  // - JVMTI fires MethodExit for the native method
  // - But GetFrameLocation returns the calling Java method that invoked it
  // - Native methods may not appear as distinct stack frames, particularly when
  //   inlined/intrinsified by the JVM
  // We trust the methodId parameter from JVMTI rather than attempting to verify it
  // against the stack, as the stack may not reflect native method calls as separate frames.
  jmethodID frameMethodId = nullptr;
  jlocation location = -1;
  CHECK_JVMTI(jvmti->GetFrameLocation(thread, 0, &frameMethodId, &location));
  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  char* signature = NULL;
  CHECK_JVMTI(jvmti->GetMethodName(methodId, NULL, &signature, NULL));
  char* closingParen = strchr(signature, ')');
  CHECK(closingParen != NULL);
  // The return type is the first character after the ')' - for objects this will be L
  char returnType = closingParen[1];
  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) signature));

  ScopedLocalRef<jobject> box(jni, Box(jni, returnType, return_value));

  // TODO: surface return_value (it's complicated because its a union)
  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnMethodExit,
      methodIdAsLong,
      location,
      count,
      box.get(),
      was_popped_by_exception);
  callbacksAllowed = true;
}

static void AgentMain(jvmtiEnv* jvmti, JNIEnv* jni, [[maybe_unused]] void* arg) {
  LOG(DEBUG) << "Running AgentMain";
  // store jvmti in a global data
  gdata = (GlobalAgentData*) calloc(1, sizeof(GlobalAgentData));

  gdata->jvmti = jvmti;
  LOG(DEBUG) << "jvmti stored";

  // Options contains the stoic dir
  std::string stoicDir = GetAgentInfo(jvmti)->options;
  LOG(DEBUG) << "Found stoicDir: " << stoicDir.c_str();


  // In order to setup the ClassLoader correctly, we need to chain it to the
  // Application ClassLoader. Otherwise we might end up with duplicate classes
  // (and weird class casting problems). We find the application class loader
  // via Looper.getMainLooper().getThread().getContextClassLoader(), which is
  // considerably more verbose to express via JNI.

  LOG(DEBUG) << "Looking for Looper";
  ScopedLocalRef<jclass> clsLooper(jni, jni->FindClass("android/os/Looper"));
  CHECK(clsLooper.get() != nullptr);
  LOG(DEBUG) << "Found Looper.class";

  jmethodID mthGetMainLooper = jni->GetStaticMethodID(clsLooper.get(), "getMainLooper", "()Landroid/os/Looper;");
  CHECK(mthGetMainLooper != nullptr);
  ScopedLocalRef<jobject> mainLooper(jni, jni->CallStaticObjectMethod(clsLooper.get(), mthGetMainLooper));
  CHECK(mainLooper.get() != nullptr);

  jmethodID mthGetThread = jni->GetMethodID(clsLooper.get(), "getThread", "()Ljava/lang/Thread;");
  CHECK(mthGetThread != nullptr);
  ScopedLocalRef<jobject> mainThread(jni, jni->CallObjectMethod(mainLooper.get(), mthGetThread));

  ScopedLocalRef<jclass> clsThread(jni, jni->FindClass("java/lang/Thread"));
  CHECK(clsThread.get() != nullptr);

  jmethodID mthGetContextClassLoader = jni->GetMethodID(clsThread.get(), "getContextClassLoader", "()Ljava/lang/ClassLoader;");
  CHECK(mthGetContextClassLoader != nullptr);

  ScopedLocalRef<jobject> originalClassLoader(jni, jni->CallObjectMethod(mainThread.get(), mthGetContextClassLoader));
  CHECK(originalClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found originalClassLoader";


  //
  // Setup args that we need for our ClassLoader
  //

  std::string stoicApkChars = std::string(stoicDir.c_str()) + std::string("/stoic-jvmti-attach.apk");
  LOG(DEBUG) << "stoicApkChars: " << stoicApkChars.c_str();

  std::string dexOutputDirChars = std::string(stoicDir.c_str()) + std::string("/dexout");
  LOG(DEBUG) << "dexOutputDirChars: " << dexOutputDirChars.c_str();

  ScopedLocalRef<jstring> stoicApkString(jni, jni->NewStringUTF(stoicApkChars.c_str()));
  CHECK(stoicApkString.get() != nullptr);

  ScopedLocalRef<jstring> dexOutputDirString(jni, jni->NewStringUTF(dexOutputDirChars.c_str()));
  CHECK(dexOutputDirString.get() != nullptr);


  //
  // Construct a new ClassLoader for stoic-jvmti-attach.apk
  //

  ScopedLocalRef<jclass> klass_DexClassLoader(jni, jni->FindClass("dalvik/system/DexClassLoader"));
  CHECK(klass_DexClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found DexClassLoader";

  jmethodID method_DexClassLoader_ctor = jni->GetMethodID(
      klass_DexClassLoader.get(),
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
  CHECK(method_DexClassLoader_ctor != nullptr);

  ScopedLocalRef<jobject> dexClassLoader(jni, jni->NewObject(
      klass_DexClassLoader.get(),
      method_DexClassLoader_ctor,
      stoicApkString.get(),
      dexOutputDirString.get(),
      nullptr,
      originalClassLoader.get()));
  CHECK(dexClassLoader.get() != nullptr);
  LOG(DEBUG) << "Constructed dexClassLoader";


  //
  // RegisterNatives (to connect Stoic APIs with their JNI implementations)
  //

  ScopedLocalRef<jclass> klass_ClassLoader(jni, jni->FindClass("java/lang/ClassLoader"));
  CHECK(klass_ClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found ClassLoader";

  jmethodID method_ClassLoader_loadClass = jni->GetMethodID(klass_ClassLoader.get(), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  CHECK(method_ClassLoader_loadClass != nullptr);
  LOG(DEBUG) << "Found loadClass method";

  {
    ScopedLocalRef<jstring> stoicJvmtiVmClassName(jni, jni->NewStringUTF("com.squareup.stoic.jvmti.VirtualMachine"));
    CHECK(stoicJvmtiVmClassName.get() != nullptr);

    ScopedLocalRef<jclass> klass_stoicJvmtiVm(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiVmClassName.get()));
    CHECK(klass_stoicJvmtiVm.get() != nullptr);
    LOG(DEBUG) << "Found stoic.jvmti.VirtualMachine class";
    gdata->stoicJvmtiVmClass = (jclass) jni->NewGlobalRef(klass_stoicJvmtiVm.get());
  }

  // JvmtiMethod stuff
  {
    ScopedLocalRef<jstring> stoicJvmtiMethodName(jni, jni->NewStringUTF("com.squareup.stoic.jvmti.JvmtiMethod"));
    CHECK(stoicJvmtiMethodName.get() != nullptr);

    ScopedLocalRef<jclass> klass_stoicJvmtiMethod(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiMethodName.get()));
    CHECK(klass_stoicJvmtiMethod.get() != nullptr);
    gdata->stoicJvmtiMethodClass = (jclass) jni->NewGlobalRef(klass_stoicJvmtiMethod.get());

    gdata->stoicJvmtiMethodCtor = jni->GetMethodID(gdata->stoicJvmtiMethodClass, "<init>", "(J)V");
    CHECK(gdata->stoicJvmtiMethodCtor != NULL);

    gdata->stoicJvmtiMethodMethodId = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "methodId", "J");
    CHECK(gdata->stoicJvmtiMethodMethodId != NULL);

    gdata->stoicJvmtiMethodPrivateClazz = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateClazz", "Ljava/lang/Class;");
    CHECK(gdata->stoicJvmtiMethodPrivateClazz != NULL);

    gdata->stoicJvmtiMethodPrivateName = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateName", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateName != NULL);

    gdata->stoicJvmtiMethodPrivateSignature = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateSignature", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateSignature != NULL);

    gdata->stoicJvmtiMethodPrivateGeneric = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateGeneric", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateGeneric != NULL);

    gdata->stoicJvmtiMethodPrivateStartLocation = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateStartLocation", "J");
    CHECK(gdata->stoicJvmtiMethodPrivateStartLocation != NULL);

    gdata->stoicJvmtiMethodPrivateEndLocation = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateEndLocation", "J");
    CHECK(gdata->stoicJvmtiMethodPrivateEndLocation != NULL);

    gdata->stoicJvmtiMethodPrivateArgsSize = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateArgsSize", "I");
    CHECK(gdata->stoicJvmtiMethodPrivateArgsSize != NULL);

    gdata->stoicJvmtiMethodPrivateMaxLocals = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateMaxLocals", "I");
    CHECK(gdata->stoicJvmtiMethodPrivateMaxLocals != NULL);

    gdata->stoicJvmtiMethodPrivateModifiers = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateModifiers", "I");
    CHECK(gdata->stoicJvmtiMethodPrivateModifiers != NULL);
  }

  // JvmtiField stuff
  {
    ScopedLocalRef<jstring> stoicJvmtiFieldName(jni, jni->NewStringUTF("com.squareup.stoic.jvmti.JvmtiField"));
    CHECK(stoicJvmtiFieldName.get() != nullptr);

    ScopedLocalRef<jclass> klass_stoicJvmtiField(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiFieldName.get()));
    CHECK(klass_stoicJvmtiField.get() != nullptr);
    gdata->stoicJvmtiFieldClass = (jclass) jni->NewGlobalRef(klass_stoicJvmtiField.get());

    gdata->stoicJvmtiFieldCtor = jni->GetMethodID(gdata->stoicJvmtiFieldClass, "<init>", "(Ljava/lang/Class;J)V");
    CHECK(gdata->stoicJvmtiFieldCtor != NULL);

    gdata->stoicJvmtiFieldClazz = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "clazz", "Ljava/lang/Class;");
    CHECK(gdata->stoicJvmtiFieldClazz != NULL);

    gdata->stoicJvmtiFieldFieldId = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "fieldId", "J");
    CHECK(gdata->stoicJvmtiFieldFieldId != NULL);

    gdata->stoicJvmtiFieldPrivateName = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "privateName", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiFieldPrivateName != NULL);

    gdata->stoicJvmtiFieldPrivateSignature = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "privateSignature", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiFieldPrivateSignature != NULL);

    gdata->stoicJvmtiFieldPrivateGeneric = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "privateGeneric", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiFieldPrivateGeneric != NULL);

    gdata->stoicJvmtiFieldPrivateModifiers = jni->GetFieldID(gdata->stoicJvmtiFieldClass, "privateModifiers", "I");
    CHECK(gdata->stoicJvmtiFieldPrivateModifiers != NULL);
  }

  // Boxing stuff
  {
    // TODO: this leaks local refs
    gdata->booleanClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Boolean"));
    gdata->byteClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Byte"));
    gdata->characterClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Character"));
    gdata->shortClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Short"));
    gdata->integerClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Integer"));
    gdata->longClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Long"));
    gdata->floatClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Float"));
    gdata->doubleClass = (jclass) jni->NewGlobalRef(jni->FindClass("java/lang/Double"));

    gdata->booleanCtor = jni->GetMethodID(gdata->booleanClass, "<init>", "(Z)V");
    gdata->byteCtor = jni->GetMethodID(gdata->byteClass, "<init>", "(B)V");
    gdata->characterCtor = jni->GetMethodID(gdata->characterClass, "<init>", "(C)V");
    gdata->shortCtor = jni->GetMethodID(gdata->shortClass, "<init>", "(S)V");
    gdata->integerCtor = jni->GetMethodID(gdata->integerClass, "<init>", "(I)V");
    gdata->longCtor = jni->GetMethodID(gdata->longClass, "<init>", "(J)V");
    gdata->floatCtor = jni->GetMethodID(gdata->floatClass, "<init>", "(F)V");
    gdata->doubleCtor = jni->GetMethodID(gdata->doubleClass, "<init>", "(D)V");
  }

  gdata->nativeCallbackOnBreakpoint = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnBreakpoint", "(JJI)V");
  gdata->nativeCallbackOnMethodEntry = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnMethodEntry", "(JJI)V");
  gdata->nativeCallbackOnMethodExit = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnMethodExit", "(JJILjava/lang/Object;Z)V");

  JNINativeMethod methods[] = {
    {"nativeInstances",                 "(Ljava/lang/Class;Z)[Ljava/lang/Object;",                      (void *)&Jvmti_VirtualMachine_nativeInstances},
    {"nativeSubclasses",                "(Ljava/lang/Class;)[Ljava/lang/Class;",                        (void *)&Jvmti_VirtualMachine_nativeSubclasses},
    {"nativeGetMethodId",               "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)J",     (void *)&Jvmti_VirtualMachine_nativeGetMethodId},
    {"nativeSetBreakpoint",             "(JJ)V",                                                        (void *)&Jvmti_VirtualMachine_nativeSetBreakpoint},
    {"nativeClearBreakpoint",           "(JJ)V",                                                        (void *)&Jvmti_VirtualMachine_nativeClearBreakpoint},
    {"nativeGetMethodCoreMetadata",     "(Lcom/squareup/stoic/jvmti/JvmtiMethod;)V",                    (void *)&Jvmti_VirtualMachine_nativeGetMethodCoreMetadata},
    {"nativeGetFieldCoreMetadata",      "(Lcom/squareup/stoic/jvmti/JvmtiField;)V",                     (void *)&Jvmti_VirtualMachine_nativeGetFieldCoreMetadata},
    {"nativeGetLocalVariables",         "(J)[Lcom/squareup/stoic/jvmti/LocalVariable;",                 (void *)&Jvmti_VirtualMachine_nativeGetLocalVariables},
    {"nativeGetLocalObject",            "(Ljava/lang/Thread;II)Ljava/lang/Object;",                     (void *)&Jvmti_VirtualMachine_nativeGetLocalObject},
    {"nativeGetLocalInt",               "(Ljava/lang/Thread;II)I",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalInt},
    {"nativeGetLocalLong",              "(Ljava/lang/Thread;II)J",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalLong},
    {"nativeGetLocalFloat",             "(Ljava/lang/Thread;II)F",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalFloat},
    {"nativeGetLocalDouble",            "(Ljava/lang/Thread;II)D",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalDouble},
    {"nativeGetClassMethods",           "(Ljava/lang/Class;)[Lcom/squareup/stoic/jvmti/JvmtiMethod;",   (void *)&Jvmti_VirtualMachine_nativeGetClassMethods},
    {"nativeGetClassFields",            "(Ljava/lang/Class;)[Lcom/squareup/stoic/jvmti/JvmtiField;",    (void *)&Jvmti_VirtualMachine_nativeGetClassFields},
    {"nativeToReflectedField",          "(Ljava/lang/Class;JZ)Ljava/lang/reflect/Field;",               (void *)&Jvmti_VirtualMachine_nativeToReflectedField},
    {"nativeToReflectedMethod",         "(Ljava/lang/Class;JZ)Ljava/lang/Object;",                      (void *)&Jvmti_VirtualMachine_nativeToReflectedMethod},
    {"nativeFromReflectedMethod",       "(Ljava/lang/reflect/Method;)J",                                (void *)&Jvmti_VirtualMachine_nativeFromReflectedMethod},
    {"nativeGetClassSignature",         "(Ljava/lang/Class;)Ljava/lang/String;",                        (void *)&Jvmti_VirtualMachine_nativeGetClassSignature},
    {"nativeMethodEntryCallbacks",      "(Ljava/lang/Thread;Z)V",                                       (void *)&Jvmti_VirtualMachine_nativeMethodEntryCallbacks},
    {"nativeMethodExitCallbacks",       "(Ljava/lang/Thread;Z)V",                                       (void *)&Jvmti_VirtualMachine_nativeMethodExitCallbacks},
  };

  CHECK(jni->RegisterNatives(gdata->stoicJvmtiVmClass, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK);


  //
  // Call AndroidServerJarKt.main to start the Android server
  //

  ScopedLocalRef<jstring> androidServerMainClassName(jni, jni->NewStringUTF(
      "com.squareup.stoic.target.runtime.AndroidServerJarKt"));
  CHECK(androidServerMainClassName.get() != nullptr);

  ScopedLocalRef<jclass> klass_AndroidServerJarKt(jni, (jclass) jni->CallObjectMethod(
      dexClassLoader.get(),
      method_ClassLoader_loadClass,
      androidServerMainClassName.get()));
  CHECK(klass_AndroidServerJarKt.get() != nullptr);
  LOG(DEBUG) << "Found AndroidServerJarKt class";

  ScopedLocalRef<jclass> klass_Method(jni, jni->FindClass("java/lang/reflect/Method"));
  CHECK(klass_Method.get() != nullptr);
  LOG(DEBUG) << "Found Method";

  jmethodID method_AndroidServerJarKt_main = jni->GetStaticMethodID(
      klass_AndroidServerJarKt.get(),
      "main",
      "(Ljava/lang/String;)V");
  CHECK(method_AndroidServerJarKt_main != nullptr);
  LOG(DEBUG) << "Found AndroidServerJarKt.main method";

  ScopedLocalRef<jstring> stoicDirString(jni, jni->NewStringUTF(stoicDir.c_str()));
  CHECK(stoicDirString != nullptr);
  LOG(DEBUG) << "Constructed stoicDirString";

  jni->CallStaticVoidMethod(klass_AndroidServerJarKt.get(), method_AndroidServerJarKt_main, stoicDirString.get());


  //
  // Done
  //

  LOG(DEBUG) << "Returned from main";
}

static void CbVmInit(jvmtiEnv* jvmti, JNIEnv* env, [[maybe_unused]] jthread thr) {
  LOG(DEBUG) << "Running CbVmInit";

  // Create a Thread object.
  ScopedLocalRef<jobject> thread_name(env, env->NewStringUTF("Agent Thread"));
  if (thread_name.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }
  ScopedLocalRef<jclass> thread_klass(env, env->FindClass("java/lang/Thread"));
  if (thread_klass.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }
  ScopedLocalRef<jobject> thread(env, env->AllocObject(thread_klass.get()));
  if (thread.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }

  env->CallNonvirtualVoidMethod(
      thread.get(),
      thread_klass.get(),
      env->GetMethodID(thread_klass.get(), "<init>", "(Ljava/lang/String;)V"),
      thread_name.get());
  env->CallVoidMethod(thread.get(), env->GetMethodID(thread_klass.get(), "setPriority", "(I)V"), 1);
  env->CallVoidMethod(
      thread.get(), env->GetMethodID(thread_klass.get(), "setDaemon", "(Z)V"), JNI_TRUE);

  jvmti->RunAgentThread(thread.get(), AgentMain, nullptr, JVMTI_THREAD_MIN_PRIORITY);
}

const int kArtTiVersion = 0x70010200;

template <bool kIsOnLoad>
static jint AgentStart(JavaVM* vm, char* options, [[maybe_unused]] void* reserved) {
  jvmtiEnv* jvmti = nullptr;

  if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK ||
      jvmti == nullptr) {

    // Retry with kArtTiVersion (for non-debuggable APKs on rooted devices)
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), kArtTiVersion) != JNI_OK ||
        jvmti == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "stoic", "unable to obtain JVMTI env (tried 0x%x)\n", kArtTiVersion);
      return JNI_ERR;
    } else {
      __android_log_print(ANDROID_LOG_ERROR, "stoic", "successfully obtained JVMTI env (via 0x%x)\n", kArtTiVersion);
    }
  }
  std::string sopts(options);
  AgentInfo* ai = new AgentInfo;
  ai->options = sopts;

  // Request can_tag_objects (required for heap iteration)
  jvmtiCapabilities tagCap = {.can_tag_objects = JNI_TRUE};
  CHECK_JVMTI(jvmti->AddCapabilities(&tagCap));

  // Try to add breakpoint capability - not available on API 26
  jvmtiCapabilities breakpointCap = {.can_generate_breakpoint_events = JNI_TRUE};
  int breakpointResult = jvmti->AddCapabilities(&breakpointCap);
  if (breakpointResult != JVMTI_ERROR_NONE) {
    __android_log_print(
        ANDROID_LOG_WARN,
        "stoic",
        "Breakpoint events not available (this is expected on API 26)\n"
    );
  } else {
    __android_log_print(ANDROID_LOG_INFO, "stoic", "Breakpoint events are available\n");
  }

  // Try to add method entry/exit capabilities - these aren't available on API 26-29
  jvmtiCapabilities entryExitCaps = {
    .can_generate_method_entry_events = JNI_TRUE,
    .can_generate_method_exit_events = JNI_TRUE,
  };
  int entryExitCapsResult = jvmti->AddCapabilities(&entryExitCaps);
  if (entryExitCapsResult != JVMTI_ERROR_NONE) {
    __android_log_print(
        ANDROID_LOG_WARN,
        "stoic",
        "Method entry/exit capabilities not available (this is expected on API 26-29)\n"
    );
  } else {
    __android_log_print(
        ANDROID_LOG_INFO,
        "stoic",
        "Method entry/exit capabilities are available\n"
    );
  }

//{
//  // Entry/exit capabilities must be added at load time, but they aren't
//  // available on API 29 and below

//  jvmtiCapabilities entryExitCaps = {
//    .can_generate_method_entry_events = JNI_TRUE,
//    .can_generate_method_exit_events = JNI_TRUE,
//  };

//  int entryExitCapsResult = jvmti->AddCapabilities(&entryExitCaps);
//  if (entryExitCapsResult != JVMTI_ERROR_NONE) {
//    __android_log_print(
//        ANDROID_LOG_WARN,
//        "stoic",
//        "Failed to add method entry/exit capabilities (this is expected on API 29 and below)\n"
//    );
//  } else {
//    __android_log_print(
//        ANDROID_LOG_WARN,
//        "stoic",
//        "Method entry/exit capabilities added successfully\n"
//    );
//  }
//}

  jvmtiEventCallbacks cb{
    .VMInit = CbVmInit,
    .Breakpoint = CbBreakpoint,
    .MethodEntry = CbMethodEntry,
    .MethodExit = CbMethodExit,
  };
  CHECK_JVMTI(jvmti->SetEventCallbacks(&cb, sizeof(cb)));
  CHECK_JVMTI(jvmti->SetEnvironmentLocalStorage(reinterpret_cast<void*>(ai)));

  // Only enable breakpoint events if we have the capability
  if (breakpointResult == JVMTI_ERROR_NONE) {
    CHECK_JVMTI(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr /* all threads */));
  }

  if (kIsOnLoad) {
    LOG(DEBUG) << "kIsOnLoad";
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr);
  } else {
    LOG(DEBUG) << "!kIsOnLoad";
    JNIEnv* jni = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&jni), JNI_VERSION_1_2);
    jthread thr;
    jvmti->GetCurrentThread(&thr);
    CbVmInit(jvmti, jni, thr);
  }


  // TODO: These LOG statements don't work on older Android devices
  LOG(DEBUG) << "Succeeded!";

  return JNI_OK;
}

// Late attachment (e.g. 'am attach-agent').
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
  __android_log_print(ANDROID_LOG_INFO, "stoic", "Agent_OnAttach\n");
  return AgentStart<false>(vm, options, reserved);
}

// Early attachment
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* jvm, char* options, void* reserved) {
  __android_log_print(ANDROID_LOG_INFO, "stoic", "Agent_OnLoad\n");
  return AgentStart<true>(jvm, options, reserved);
}
