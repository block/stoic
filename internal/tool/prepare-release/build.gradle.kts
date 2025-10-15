plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.application)
}

dependencies {
  implementation(project(":bridge"))
}

application {
  mainClass.set("com.squareup.stoic.preparerelease.MainKt")
}

tasks.named<JavaExec>("run") {
  workingDir = File(System.getProperty("user.dir"))
}
