import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    // Plugins need to be declared here to avoid warnings like:
    //   The Kotlin Gradle plugin was loaded multiple times in different
    //   subprojects, which is not supported and may break the build.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

repositories {
    google()
    mavenCentral()
}

allprojects {
  plugins.withId("java") {
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(17))
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java
      .cast(extensions.getByName("kotlin"))
      .jvmToolchain(17)
  }
}

// For Android modules:
subprojects {
  plugins.withId("com.android.application") {
    extensions.configure<com.android.build.gradle.AppExtension>("android") {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }
  plugins.withId("com.android.library") {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }
}

val prebuiltDir = rootProject.file("../prebuilt")
val versionFile = prebuiltDir.resolve("STOIC_VERSION")
val versionName = versionFile.readText().trim()

val stoicProps = Properties().apply {
    prebuiltDir.resolve("stoic.properties").reader().use { load(it) }
}

val androidMinSdk = stoicProps.getProperty("android_min_sdk") ?: error("Missing android_min_sdk")
val androidCompileSdk = stoicProps.getProperty("android_compile_sdk") ?: error("Missing android_compile_sdk")
val androidTargetSdk = stoicProps.getProperty("android_target_sdk") ?: error("Missing android_target_sdk")
val androidBuildToolsVersion = stoicProps.getProperty("android_build_tools_version") ?: error("Missing android_build_tools_version")

// Needed for :bridge, since it needs it during configuration phase
extra["stoic.version_name"] = versionName

subprojects {
    extra["stoic.android_min_sdk"] = androidMinSdk
    extra["stoic.android_compile_sdk"] = androidCompileSdk
    extra["stoic.android_target_sdk"] = androidTargetSdk
    extra["stoic.android_build_tools_version"] = androidBuildToolsVersion
    extra["stoic.version_name"] = versionName
    extra["stoic.version_code"] = versionCodeFromVersionName(versionName)

    plugins.withId("java") {
        val jarTask = tasks.named<Jar>("jar")

        // Builds .apk, preserving the manifest
        tasks.register<JavaExec>("apk") {
            dependsOn(jarTask)

            val jarFile = jarTask.flatMap { it.archiveFile }.map { it.asFile }
            val apkFile = jarFile.map { File(it.path.replace(".jar", ".apk")) }

            val jarToApkPreserveManifest = project(":internal:tool:jar-to-apk-preserve-manifest")
            classpath = jarToApkPreserveManifest
              .extensions
              .getByType<JavaPluginExtension>()
              .sourceSets
              .getByName("main")
              .runtimeClasspath
            mainClass.set(jarToApkPreserveManifest.the<JavaApplication>().mainClass)
            inputs.file(jarFile)
            outputs.file(apkFile)

            // Set args lazily, during execution
            doFirst {
                args = listOf(
                  jarFile.get().absolutePath,
                  apkFile.get().absolutePath
                )
            }
        }
    }

    val projectPathSlug = project.path.removePrefix(":").replace(":", "-")
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
              "Implementation-Title" to "stoic-$projectPathSlug",
              "Implementation-Version" to versionName
            )
        }
    }
}
