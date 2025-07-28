import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.application)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.graalvm.buildtools.native") version("0.10.6")
    kotlin("kapt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)
    implementation(kotlin("stdlib"))
    implementation(project(":common"))
    implementation(project(":bridge"))
    implementation(project(":internal:tool:d8-preserve-manifest"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

application {
  mainClass.set("com.squareup.stoic.host.main.HostMainKt")
}

graalvmNative {
    binaries {
        named("main") {
            //
            // Fixes: Error: Classes that should be initialized at run time got initialized during image building:
            //   kotlin.DeprecationLevel was unintentionally initialized at build time. To see why kotlin.DeprecationLevel got initialized use --trace-class-initialization=kotlin.DeprecationLevel
            //   
            buildArgs.add("--initialize-at-build-time=kotlin.DeprecationLevel")

            imageName.set("stoic")
            mainClass.set("com.squareup.stoic.host.main.HostMainKt")
        }
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.squareup.stoic.host.main.HostMainKt")
    }
    // To include all dependencies in the JAR file, uncomment the following lines:
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
}
