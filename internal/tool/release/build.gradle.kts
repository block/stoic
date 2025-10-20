plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.application)
}

dependencies {
  implementation(project(":protocol"))
}

application {
  mainClass.set("com.squareup.stoic.release.MainKt")
}

tasks.named<JavaExec>("run") {
  workingDir = File(System.getProperty("user.dir"))
  standardInput = System.`in`
}
