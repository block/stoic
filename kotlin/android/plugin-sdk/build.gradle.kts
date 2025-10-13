import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
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

tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

artifacts {
    add("archives", tasks["sourcesJar"])
}

configure<MavenPublishBaseExtension> {
  configure(
    KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true)
  )
}

tasks.register("printPublishingInfo") {
    doLast {
        // Print publication coordinates
        publishing.publications.withType<MavenPublication>().forEach { pub ->
            println("Publication: ${pub.name}")
            println("  groupId:    ${pub.groupId}")
            println("  artifactId: ${pub.artifactId}")
            println("  version:    ${pub.version}")
        }

        // Print repository names and URLs
        publishing.repositories.withType(MavenArtifactRepository::class.java).forEach { repo ->
            println("Repository: ${repo.name} -> ${repo.url}")
        }
    }
}
