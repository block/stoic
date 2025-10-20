package com.squareup.stoic.bridge

import com.squareup.stoic.generated.GeneratedStoicProperties

// Generated code can cause problems for IDEs. This bridge provides a stable API
// that delegates to generated code. IDEs see this file, not the generated one.
//
// To resolve GeneratedStoicProperties:
//   ./gradlew :common:build
//   and then Sync Project with Gradle Files
object StoicProperties {
  const val STOIC_VERSION_NAME: String = GeneratedStoicProperties.STOIC_VERSION_NAME
  const val ANDROID_BUILD_TOOLS_VERSION: String = GeneratedStoicProperties.ANDROID_BUILD_TOOLS_VERSION
  const val ANDROID_COMPILE_SDK: Int = GeneratedStoicProperties.ANDROID_COMPILE_SDK
  const val ANDROID_MIN_SDK: Int = GeneratedStoicProperties.ANDROID_MIN_SDK
  const val ANDROID_TARGET_SDK: Int = GeneratedStoicProperties.ANDROID_TARGET_SDK
}
