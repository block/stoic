import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Properties

buildscript {
  repositories {
    google()
    mavenCentral()
  }

  dependencies {
    classpath(libs.vanniktech.maven.publish.plugin)
  }
}

plugins {
    // Plugins need to be declared here to avoid warnings like:
    //   The Kotlin Gradle plugin was loaded multiple times in different
    //   subprojects, which is not supported and may break the build.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.maven.publish.base) apply false
}

val prebuiltDir = rootProject.file("prebuilt")
val versionFile = prebuiltDir.resolve("STOIC_VERSION")
val versionName = versionFile.readText().trim()

val stoicProps = Properties().apply {
    prebuiltDir.resolve("stoic.properties").reader().use { load(it) }
}

val androidMinSdk = stoicProps.getProperty("android_min_sdk") ?: error("Missing android_min_sdk")
val androidCompileSdk = stoicProps.getProperty("android_compile_sdk") ?: error("Missing android_compile_sdk")
val androidTargetSdk = stoicProps.getProperty("android_target_sdk") ?: error("Missing android_target_sdk")
val androidBuildToolsVersion = stoicProps.getProperty("android_build_tools_version") ?: error("Missing android_build_tools_version")

// Needed for :bridge, since it needs it during configuration phase
extra["stoic.version_name"] = versionName

allprojects {
  plugins.withId("java") {
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(17))
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java
      .cast(extensions.getByName("kotlin"))
      .jvmToolchain(17)
  }

  group = "com.squareup.stoic"
  version = versionName

  repositories {
    mavenCentral()
    google()
  }

  plugins.withId("com.vanniktech.maven.publish.base") {
    configure<PublishingExtension> {
      repositories {
        maven {
          name = "testMaven"
          url = rootProject.layout.buildDirectory.dir("testMaven").get().asFile.toURI()
        }

        /*
         * Want to push to an internal repository for testing?
         * Set the following properties in ~/.gradle/gradle.properties.
         *
         * internalUrl=YOUR_INTERNAL_URL
         * internalUsername=YOUR_USERNAME
         * internalPassword=YOUR_PASSWORD
         *
         * Then run the following command to publish a new internal release:
         *
         * ./gradlew publishAllPublicationsToInternalRepository -DRELEASE_SIGNING_ENABLED=false
         */
        val internalUrl = providers.gradleProperty("internalUrl").orNull
        if (internalUrl != null) {
          maven {
            name = "internal"
            url = URI(internalUrl)
            credentials {
              username = providers.gradleProperty("internalUsername").get()
              password = providers.gradleProperty("internalPassword").get()
            }
          }
        }
      }
    }
    configure<MavenPublishBaseExtension> {
      configure(
        KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true)
      )

      publishToMavenCentral(automaticRelease = true)

      signAllPublications()

      pom {
        description.set("Run code within any debuggable Android process, without modifying its APK")
        name.set(project.name)
        url.set("https://github.com/block/stoic/")

        licenses {
          license {
            name.set("The Apache Software License, Version 2.0")
            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }

        developers {
          developer {
            id.set("block")
            name.set("Block")
          }
        }

        scm {
          url.set("https://github.com/block/stoic/")
          connection.set("scm:git:https://github.com/block/stoic.git")
          developerConnection.set("scm:git:ssh://git@github.com/block/stoic.git")
        }
      }
    }
    tasks.register("printPublishingInfo") {
      doLast {
        val publishing = project.extensions.findByType(
          org.gradle.api.publish.PublishingExtension::class.java
        ) ?: run {
          println("No publishing extension for ${project.path}")
          return@doLast
        }

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
  }
}

// For Android modules:
subprojects {
  plugins.withId("com.android.application") {
    extensions.configure<com.android.build.gradle.AppExtension>("android") {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }
  plugins.withId("com.android.library") {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }
}


subprojects {
    extra["stoic.android_min_sdk"] = androidMinSdk
    extra["stoic.android_compile_sdk"] = androidCompileSdk
    extra["stoic.android_target_sdk"] = androidTargetSdk
    extra["stoic.android_build_tools_version"] = androidBuildToolsVersion
    extra["stoic.version_name"] = versionName
    extra["stoic.version_code"] = versionCodeFromVersionName(versionName)

    plugins.withId("java") {
        val jarTask = tasks.named<Jar>("jar")

        // Builds .apk, preserving the manifest
        tasks.register<JavaExec>("apk") {
            dependsOn(jarTask)

            val jarFile = jarTask.flatMap { it.archiveFile }.map { it.asFile }
            val apkFile = jarFile.map { File(it.path.replace(".jar", ".apk")) }

            val jarToApkPreserveManifest = project(":internal:tool:jar-to-apk-preserve-manifest")
            classpath = jarToApkPreserveManifest
              .extensions
              .getByType<JavaPluginExtension>()
              .sourceSets
              .getByName("main")
              .runtimeClasspath
            mainClass.set(jarToApkPreserveManifest.the<JavaApplication>().mainClass)
            inputs.file(jarFile)
            outputs.file(apkFile)

            // Set args lazily, during execution
            doFirst {
                args = listOf(
                  jarFile.get().absolutePath,
                  apkFile.get().absolutePath
                )
            }
        }
    }

    val projectPathSlug = project.path.removePrefix(":").replace(":", "-")
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
              "Implementation-Title" to "stoic-$projectPathSlug",
              "Implementation-Version" to versionName
            )
        }
    }
}

// Distribution task - assembles all artifacts into build/distributions/
val buildDistribution by tasks.registering {
    description = "Builds and assembles all Stoic artifacts into build/distributions/"
    group = "build"

    // Depend on all the subproject builds
    dependsOn(
        ":host:main:assemble",
        ":host:main:nativeCompile",
        ":target:plugin-sdk:assemble",
        ":target:runtime:attached:apk",
        ":demo-plugin:helloworld:apk",
        ":demo-plugin:appexitinfo:apk",
        ":demo-plugin:breakpoint:apk",
        ":demo-plugin:crasher:apk",
        ":demo-plugin:testsuite:apk",
        ":demo-app:without-sdk:assembleDebug",
        ":demo-app:with-sdk:assembleRelease",
        ":native:buildNative"
    )

    val releaseDir = layout.buildDirectory.dir("distributions").get().asFile
    val syncDir = File(releaseDir, "sync")

    doLast {
        // Create directory structure
        File(releaseDir, "jar").mkdirs()
        File(releaseDir, "sdk").mkdirs()
        File(releaseDir, "bin/darwin-arm64").mkdirs()
        File(syncDir, "plugins").mkdirs()
        File(syncDir, "stoic").mkdirs()
        File(syncDir, "bin").mkdirs()
        File(syncDir, "apk").mkdirs()

        // Copy prebuilt files
        copy {
            from("prebuilt")
            into(releaseDir)
        }

        // Copy host artifacts
        copy {
            from("host/main/build/libs/main-$versionName.jar")
            into("$releaseDir/jar")
            rename { "stoic-host-main.jar" }
        }
        copy {
            from("host/main/build/native/nativeCompile/stoic")
            into("$releaseDir/bin/darwin-arm64")
        }

        // Copy plugin SDK
        copy {
            from("target/plugin-sdk/build/libs/plugin-sdk-$versionName.jar")
            into("$releaseDir/sdk")
            rename { "stoic-plugin-sdk.jar" }
        }
        copy {
            from("target/plugin-sdk/build/libs/plugin-sdk-$versionName-sources.jar")
            into("$releaseDir/sdk")
            rename { "stoic-plugin-sdk-sources.jar" }
        }

        // Copy runtime APK
        copy {
            from("target/runtime/attached/build/libs/attached-$versionName.apk")
            into("$syncDir/stoic")
            rename { "stoic-runtime-attached.apk" }
        }

        // Copy demo apps
        copy {
            from("demo-app/without-sdk/build/outputs/apk/debug/without-sdk-debug.apk")
            into("$syncDir/apk")
            rename { "stoic-demo-app-without-sdk-debug.apk" }
        }
        copy {
            from("demo-app/with-sdk/build/outputs/apk/release/with-sdk-release.apk")
            into("$syncDir/apk")
            rename { "stoic-demo-app-with-sdk-release.apk" }
        }

        // Copy demo plugins
        val demoPluginsDir = File(releaseDir, "demo-plugins")
        demoPluginsDir.mkdirs()
        copy {
            from("demo-plugin/appexitinfo/build/libs/appexitinfo-$versionName.apk")
            into(demoPluginsDir)
            rename { "appexitinfo.apk" }
        }
        copy {
            from("demo-plugin/breakpoint/build/libs/breakpoint-$versionName.apk")
            into(demoPluginsDir)
            rename { "breakpoint.apk" }
        }
        copy {
            from("demo-plugin/crasher/build/libs/crasher-$versionName.apk")
            into(demoPluginsDir)
            rename { "crasher.apk" }
        }
        copy {
            from("demo-plugin/helloworld/build/libs/helloworld-$versionName.apk")
            into(demoPluginsDir)
            rename { "helloworld.apk" }
        }
        copy {
            from("demo-plugin/testsuite/build/libs/testsuite-$versionName.apk")
            into(demoPluginsDir)
            rename { "testsuite.apk" }
        }

        // Set permissions on sync directory
        exec {
            commandLine("chmod", "-R", "a+rw", syncDir.absolutePath)
        }

        println()
        println()
        println("----- Stoic build completed -----")
        println()
        println()
    }
}
