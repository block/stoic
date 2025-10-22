plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.squareup.stoic.demoapp.withoutsdk"
  compileSdk = (extra["stoic.android_compile_sdk"] as String).toInt()

  defaultConfig {
    applicationId = "com.squareup.stoic.demoapp.withoutsdk"
    minSdk = (extra["stoic.android_min_sdk"] as String).toInt()
    targetSdk = (extra["stoic.android_target_sdk"] as String).toInt()
    versionCode = extra["stoic.version_code"] as Int
    versionName = extra["stoic.version_name"] as String

    testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    val jvmTarget = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = jvmTarget
    targetCompatibility = jvmTarget
  }
  kotlinOptions { jvmTarget = libs.versions.jvmTarget.get() }
  buildFeatures { viewBinding = true }
}

dependencies {
  implementation("com.android.support:appcompat-v7:28.0.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("com.android.support.test:runner:1.0.2")
  androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
}
