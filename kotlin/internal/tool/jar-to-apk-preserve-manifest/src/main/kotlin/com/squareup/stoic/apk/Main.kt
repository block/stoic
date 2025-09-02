package com.squareup.stoic.apk
import com.squareup.stoic.bridge.StoicProperties
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main(args: Array<String>) {
  println("args: ${args.joinToString(", ")}")
  val src = File(args[0])
  val dst = File(args[1])
  val tempDir = Files.createTempDirectory("jar-to-apk-preserve-manifest").toFile()
  System.err.println("temp-dir: $tempDir")
  jarToApkPreserveManifest(src, dst, tempDir)
  System.err.println("generated: ${dst.absolutePath}")
}

fun jarToApkPreserveManifest(jarFile: File, apkFile: File) {
  jarToApkPreserveManifest(
    jarFile,
    apkFile,
    tempDir = Files.createTempDirectory("jar-to-apk-preserve-manifest").toFile()
  )
}

// TODO: This is broken. The intent is to run D8, but preserve META-INF/MANIFEST.MF from the jar
fun jarToApkPreserveManifest(jarFile: File, apkFile: File, tempDir: File) {
  val androidBuildToolsVersion = StoicProperties.ANDROID_BUILD_TOOLS_VERSION
  val androidHome = System.getenv("ANDROID_HOME") ?: error("Need to set ANDROID_HOME")
  val d8Path = File("$androidHome/build-tools/$androidBuildToolsVersion/d8")
  val dexOutDir = File(tempDir, "stoic-dex")
  dexOutDir.mkdirs()

  check(
    ProcessBuilder(
      d8Path.absolutePath,
      "--min-api", StoicProperties.ANDROID_MIN_SDK.toString(),
      "--output", dexOutDir.absolutePath,
      jarFile.absolutePath
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor() == 0)

  ZipOutputStream(apkFile.outputStream().buffered()).use { zipOut ->
    dexOutDir.listFiles()?.forEach { file ->
      zipOut.putNextEntry(ZipEntry(file.name))
      file.inputStream().buffered().use { it.copyTo(zipOut) }
      zipOut.closeEntry()
    }
  }
}
