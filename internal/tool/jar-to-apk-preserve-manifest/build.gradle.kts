plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.application)
}

dependencies { implementation(project(":protocol")) }

application { mainClass.set("com.squareup.stoic.apk.MainKt") }
