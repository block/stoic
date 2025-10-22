plugins {
  alias(libs.plugins.shadow)
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

repositories {
  google()
  mavenCentral()
}

android {
  namespace = "com.squareup.stoic.plugin"
  compileSdk = 35
  defaultConfig {
    applicationId = "com.squareup.stoic.plugin"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }
  compileOptions {
    val jvmTarget = JavaVersion.VERSION_17
    sourceCompatibility = jvmTarget
    targetCompatibility = jvmTarget
  }
}

dependencies {
  val stoicPluginSdk = "${rootProject.projectDir}/../../sdk/stoic-plugin-sdk.jar"
  compileOnly(files(stoicPluginSdk))
  implementation(kotlin("stdlib"))
}
