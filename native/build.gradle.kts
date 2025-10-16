// Build native C++ JVMTI agent using existing Makefile for multiple architectures

val stoicDir = rootProject.projectDir
val nativeDir = projectDir
val distributionsDir = rootProject.layout.buildDirectory.dir("distributions").get().asFile
val syncDir = distributionsDir.resolve("sync")

// Read android_ndk_version from gradle.properties
val androidNdkVersion = rootProject.providers.gradleProperty("android.ndkVersion").get()

val androidHome = System.getenv("ANDROID_HOME")
    ?: error("ANDROID_HOME environment variable not set")

val androidNdk = "$androidHome/ndk/$androidNdkVersion"

// Build for multiple architectures
val androidArchitectures = listOf("arm64-v8a", "x86_64")

tasks.register<Exec>("buildNative") {
    workingDir = nativeDir
    commandLine("make", "-j16", "all")

    environment("ANDROID_NDK", androidNdk)
    environment("OUT_DIR", distributionsDir.absolutePath)

    inputs.files(
        fileTree(nativeDir) {
            include("*.cc", "*.h", "Makefile*")
        }
    )

    // Output files for each architecture
    androidArchitectures.forEach { arch ->
        outputs.file(syncDir.resolve("stoic/$arch/stoic-jvmti-agent.so"))
    }

    doFirst {
        println("Building native JVMTI agent for architectures: ${androidArchitectures.joinToString(", ")}")
        println("Using NDK: $androidNdk")
    }
}

tasks.register<Delete>("clean") {
    delete(distributionsDir)
}

// Make the native build part of the default build
tasks.register("assemble") {
    dependsOn("buildNative")
}

// Default task
defaultTasks("assemble")
