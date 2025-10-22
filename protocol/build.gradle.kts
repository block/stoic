import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.vanniktech.maven.publish.base)
}

repositories { mavenCentral() }

dependencies {
  implementation(kotlin("stdlib"))
  implementation(libs.kotlinx.serialization.json)
}

// Generate build-time constants
val androidMinSdk = providers.gradleProperty("android.minSdk").get()
val androidCompileSdk = providers.gradleProperty("android.compileSdk").get()
val androidTargetSdk = providers.gradleProperty("android.targetSdk").get()
val androidBuildToolsVersion = providers.gradleProperty("android.buildToolsVersion").get()

val stoicGeneratedSourceDir = layout.buildDirectory.dir("generated/stoic")
val generateCode by
  tasks.registering {
    inputs.property("version_name", rootProject.extra["stoic.version_name"] as String)
    outputs.dir(stoicGeneratedSourceDir)

    doLast {
      val versionName = inputs.properties["version_name"] as String

      // Generate the actual properties (hidden from IDE in build/generated)
      val props =
        stoicGeneratedSourceDir
          .get()
          .asFile
          .resolve("com/squareup/stoic/generated/GeneratedStoicProperties.kt")
      props.parentFile.mkdirs()
      props.writeText(
        """
            package com.squareup.stoic.generated

            internal object GeneratedStoicProperties {
                const val STOIC_VERSION_NAME = "$versionName"
                const val ANDROID_MIN_SDK = $androidMinSdk
                const val ANDROID_COMPILE_SDK = $androidCompileSdk
                const val ANDROID_TARGET_SDK = $androidTargetSdk
                const val ANDROID_BUILD_TOOLS_VERSION = "$androidBuildToolsVersion"
            }
            """
          .trimIndent()
      )

      // Copy VersionCodeFromVersionName for use in release scripts
      val versionCodeFromVersionNameText =
        rootProject.file("buildSrc/src/main/kotlin/VersionCodeFromVersionName.kt").readText()

      val versionCodeFromVersionName =
        stoicGeneratedSourceDir
          .get()
          .asFile
          .resolve("com/squareup/stoic/generated/VersionCodeFromVersionName.kt")

      versionCodeFromVersionName.parentFile.mkdirs()
      versionCodeFromVersionName.writeText(
        """
             |package com.squareup.stoic.generated
             |
             |${versionCodeFromVersionNameText.replace("\n", "\n|")}
          """
          .trimMargin()
      )
    }
  }

kotlin.sourceSets["main"].kotlin.srcDir(stoicGeneratedSourceDir)

tasks.withType<KotlinCompile> {
  dependsOn(generateCode)
  kotlinOptions { jvmTarget = "17" }
}

// Make sourcesJar depend on code generation for publishing
tasks.named("sourcesJar") { dependsOn(generateCode) }
