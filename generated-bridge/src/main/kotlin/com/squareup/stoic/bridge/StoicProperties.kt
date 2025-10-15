package com.squareup.stoic.bridge

import com.squareup.stoic.generated.GeneratedStoicProperties

// To resolve GeneratedStoicProperties:
//   `./gradlew :bridge:build`
//   and then Sync Project with Gradle Files
object StoicProperties {
  const val STOIC_VERSION_NAME: String = GeneratedStoicProperties.STOIC_VERSION_NAME
  const val ANDROID_BUILD_TOOLS_VERSION: String = GeneratedStoicProperties.ANDROID_BUILD_TOOLS_VERSION
  const val ANDROID_COMPILE_SDK: Int = GeneratedStoicProperties.ANDROID_COMPILE_SDK
  const val ANDROID_MIN_SDK: Int = GeneratedStoicProperties.ANDROID_MIN_SDK
  const val ANDROID_TARGET_SDK: Int = GeneratedStoicProperties.ANDROID_TARGET_SDK
}
