package com.squareup.stoic.d8pm
import com.squareup.stoic.bridge.StoicProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main(args: Array<String>) {
  println("args: ${args.joinToString(", ")}")
  val src = File(args[0])
  val dst = File(args[1])
  val tempDir = Files.createTempDirectory("d8-preserve-manifest").toFile()
  System.err.println("temp-dir: $tempDir")
  d8PreserveManifest(src, dst, tempDir)
  System.err.println("generated: ${dst.absolutePath}")
}

fun d8PreserveManifest(jarFile: File, apkFile: File) {
  d8PreserveManifest(jarFile, apkFile, tempDir = Files.createTempDirectory("d8-preserve-manifest").toFile())
}

fun d8PreserveManifest(jarFile: File, apkFile: File, tempDir: File) {
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

  ZipOutputStream(FileOutputStream(apkFile)).use { zipOut ->
    dexOutDir.listFiles()?.forEach { file ->
      zipOut.putNextEntry(ZipEntry(file.name))
      FileInputStream(file).use { it.copyTo(zipOut) }
      zipOut.closeEntry()
    }
  }
}
