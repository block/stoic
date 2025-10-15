import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Generated code can cause problems for IDEs. To solve this, we hide generated code behind a
// bridge. i.e. Normal code never directly depends on generated code. Instead it depends on the
// bridge "interface", which in turn delegates to the generated code.

val prebuiltDir = rootProject.file("prebuilt")
val stoicProps = Properties().apply {
    prebuiltDir.resolve("stoic.properties").reader().use { load(it) }
}

val androidMinSdk = stoicProps.getProperty("android_min_sdk") ?: error("Missing android_min_sdk")
val androidCompileSdk = stoicProps.getProperty("android_compile_sdk") ?: error("Missing android_compile_sdk")
val androidTargetSdk = stoicProps.getProperty("android_target_sdk") ?: error("Missing android_target_sdk")
val androidBuildToolsVersion = stoicProps.getProperty("android_build_tools_version") ?: error("Missing android_build_tools_version")

val stoicGeneratedSourceDir = layout.buildDirectory.dir("generated/stoic")
val generateCode by tasks.registering {
    inputs.property("version_name", rootProject.extra["stoic.version_name"] as String)
    outputs.dir(stoicGeneratedSourceDir)

    doLast {
        val versionName = inputs.properties["version_name"] as String
        val props = stoicGeneratedSourceDir.get().asFile.resolve("com/squareup/stoic/generated/GeneratedStoicProperties.kt")
        props.parentFile.mkdirs()
        props.writeText(
          """
            package com.squareup.stoic.generated

            object GeneratedStoicProperties {
                const val STOIC_VERSION_NAME = "$versionName"
                const val ANDROID_MIN_SDK = $androidMinSdk
                const val ANDROID_COMPILE_SDK = $androidCompileSdk
                const val ANDROID_TARGET_SDK = $androidTargetSdk
                const val ANDROID_BUILD_TOOLS_VERSION = "$androidBuildToolsVersion"
            }
            """.trimIndent()
        )

        // AGP needs version-code at configuration time, so we have versionCodeFromVersionName
        // defined in buildSrc. We also need to be able to compute versionCodes in prepare-release.
        // To allow us to share code, we take the buildSrc src and copy it to a generated file.

        val versionCodeFromVersionNameText = rootProject.file(
          "buildSrc/src/main/kotlin/VersionCodeFromVersionName.kt"
        ).readText()

        val versionCodeFromVersionName = stoicGeneratedSourceDir.get().asFile.resolve(
          "com/squareup/stoic/generated/VersionCodeFromVersionName.kt"
        )

        versionCodeFromVersionName.parentFile.mkdirs()
        versionCodeFromVersionName.writeText(
          """
             |package com.squareup.stoic.generated
             |
             |${versionCodeFromVersionNameText.replace("\n", "\n|")}
          """.trimMargin()
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(stoicGeneratedSourceDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCode)
}

