plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.squareup.stoic.target.runtime"
    compileSdk = (extra["stoic.android_compile_sdk"] as String).toInt()

    defaultConfig {
        minSdk = (extra["stoic.android_min_sdk"] as String).toInt()
        applicationId = "com.squareup.stoic.jvmti.attach"
    }

    compileOptions {
        val jvmTarget = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = jvmTarget
        targetCompatibility = jvmTarget
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }
}

dependencies {
  implementation(project(":target:app-sdk"))
}
