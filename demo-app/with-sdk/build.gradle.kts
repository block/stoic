plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.squareup.stoic.demoapp.withsdk"
  compileSdk = (extra["stoic.android_compile_sdk"] as String).toInt()

  signingConfigs {
    create("release") {
      storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  defaultConfig {
    applicationId = "com.squareup.stoic.demoapp.withsdk"
    minSdk = (extra["stoic.android_min_sdk"] as String).toInt()
    targetSdk = (extra["stoic.android_target_sdk"] as String).toInt()
    versionCode = extra["stoic.version_code"] as Int
    versionName = extra["stoic.version_name"] as String

    testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    // The with-sdk demo exists to demonstrate the ability to use stoic in a
    // non-debuggable build, so we always make our builds non-debuggable - even
    // the "debug" variant.
    all {
      isDebuggable = false
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    val jvmTarget = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = jvmTarget
    targetCompatibility = jvmTarget
  }
  kotlinOptions {
    jvmTarget = libs.versions.jvmTarget.get()
  }
  buildFeatures {
    viewBinding = true
  }

  // TODO: this doesn't work for source dependencies, but apparently this is the way to pre-exclude
  //   from the .aar file
  packaging {
    resources {
      excludes += "META-INF/DEPENDENCIES"
    }
  }
}

dependencies {
  implementation("com.android.support:appcompat-v7:28.0.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("com.android.support.test:runner:1.0.2")
  androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
  implementation(project(":target:runtime:sdk"))
}
