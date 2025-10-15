import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.maven.publish.base)
}

repositories {
    mavenCentral()
}

val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
    ?: throw GradleException("ANDROID_HOME is not set")
val androidCompileSdk = extra["stoic.android_compile_sdk"] as String

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jetbrains.kotlin.reflect)
    compileOnly(files("$androidHome/platforms/android-$androidCompileSdk/android.jar"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
