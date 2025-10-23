import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.application)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

application { mainClass.set("com.squareup.stoic.test.ProtocolVersionClientKt") }

repositories { mavenCentral() }

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":protocol"))
  implementation(libs.kotlinx.serialization.json)
}

tasks.withType<KotlinCompile> { kotlinOptions { jvmTarget = "17" } }

tasks.jar {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes("Main-Class" to "com.squareup.stoic.test.ProtocolVersionClientKt") }
  // Include all dependencies in the JAR
  from({
    configurations.runtimeClasspath
      .get()
      .filter { it.exists() }
      .map { if (it.isDirectory) it else zipTree(it) }
  })
}
