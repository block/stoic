// Build native C++ JVMTI agent using existing Makefile

val stoicDir = rootProject.projectDir
val nativeDir = projectDir
val distributionsDir = rootProject.layout.buildDirectory.dir("distributions").get().asFile
val syncDir = distributionsDir.resolve("sync")
val targetSo = syncDir.resolve("stoic/stoic-jvmti-agent.so")

// Read android_ndk_version from stoic.properties
val stoicProps = java.util.Properties().apply {
    stoicDir.resolve("prebuilt/stoic.properties").reader().use { load(it) }
}
val androidNdkVersion = stoicProps.getProperty("android_ndk_version")
    ?: error("Missing android_ndk_version in stoic.properties")

val androidHome = System.getenv("ANDROID_HOME")
    ?: error("ANDROID_HOME environment variable not set")

val androidNdk = "$androidHome/ndk/$androidNdkVersion"

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
    inputs.dir(nativeDir.resolve("libbase"))
    inputs.dir(nativeDir.resolve("libnativehelper"))
    inputs.dir(nativeDir.resolve("fmtlib"))

    outputs.file(targetSo)

    doFirst {
        println("Building native JVMTI agent with NDK: $androidNdk")
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
