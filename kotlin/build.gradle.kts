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

        // Builds .dex.jar, preserving the manifest
        tasks.register<JavaExec>("dexJar") {
            dependsOn(jarTask)

            val jarFile = jarTask.flatMap { it.archiveFile }.map { it.asFile }
            val dexJarFile = jarFile.map { File(it.path.replace(".jar", ".dex.jar")) }

            val jarToApkPreserveManifest = project(":internal:tool:jar-to-apk-preserve-manifest")
            classpath = jarToApkPreserveManifest
              .extensions
              .getByType<JavaPluginExtension>()
              .sourceSets
              .getByName("main")
              .runtimeClasspath
            mainClass.set(jarToApkPreserveManifest.the<JavaApplication>().mainClass)
            inputs.file(jarFile)
            outputs.file(dexJarFile)

            // Set args lazily, during execution
            doFirst {
                args = listOf(
                  jarFile.get().absolutePath,
                  dexJarFile.get().absolutePath
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
