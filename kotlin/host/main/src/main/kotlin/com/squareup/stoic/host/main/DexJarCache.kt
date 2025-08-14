@file:OptIn(ExperimentalSerializationApi::class)

package com.squareup.stoic.host.main

import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.PithyException
import com.squareup.stoic.common.Sha
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logInfo
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileInputStream
import java.nio.file.*
import java.nio.file.attribute.FileTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

object DexJarCache {
  private const val VERSION = 2

  /** Root directory (${java.io.tmpdir}/dex_cache) **/
  private val cacheRoot: Path =
    Paths.get(System.getProperty("java.io.tmpdir"), ".stoic/cache/apk-$VERSION")

  init {
    Files.createDirectories(cacheRoot)
  }

  fun resolve(jarOrApkFile: File): Pair<File, String> {
    val keyDir = computeKeyDir(jarOrApkFile)
    get(keyDir, jarOrApkFile)?.let { return it }
    logInfo { "DexJarCache.get failed - regenerating sha/apk" }

    val apk = computeCachedApkPath(keyDir, jarOrApkFile)
    Files.createDirectories(keyDir)
    val metaFile = keyDir.resolve("meta")
    metaFile.deleteIfExists()

    if (jarOrApkFile.extension == "apk") {
      jarOrApkFile.copyTo(apk.toFile(), overwrite = true)
    } else {
      jarToApk(jarOrApkFile, apk.toFile())
    }

    val apkSha256Sum = updateMeta(keyDir, jarOrApkFile)
    return Pair(apk.toFile(), apkSha256Sum)
  }

  private fun jarToApk(jarFile: File, apkFile: File) {
    logBlock(LogLevel.INFO, { "dexing $jarFile to $apkFile" }) {
      val dexOutDir = createTempDirectory("stoic-dex").toFile()
      val androidBuildToolsVersion = StoicProperties.ANDROID_BUILD_TOOLS_VERSION
      val androidHome = System.getenv("ANDROID_HOME") ?: error("Need to set ANDROID_HOME")
      val d8Path = File("$androidHome/build-tools/$androidBuildToolsVersion/d8")

      check(
        ProcessBuilder(
          d8Path.absolutePath,
          "--min-api", StoicProperties.ANDROID_MIN_SDK.toString(),
          "--output", dexOutDir.absolutePath,
          jarFile.absolutePath
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor() == 0
      ) { "d8 failed" }

      ZipOutputStream(FileOutputStream(apkFile)).use { zipOut ->
        dexOutDir.listFiles()?.forEach { file ->
          zipOut.putNextEntry(ZipEntry(file.name))
          FileInputStream(file).use { it.copyTo(zipOut) }
          zipOut.closeEntry()
        }
      }
    }
  }

  /**
   * Return the cached apk for [jarOrApkFile] if present *and* still valid.
   * Returns `null` on cache miss.
   */
  private fun get(keyDir: Path, jarOrApkFile: File): Pair<File, String>? {
    val metaFile = keyDir.resolve("meta")
    if (!metaFile.exists()) {
      return null
    }

    val meta = Json.decodeFromString<ApkCacheMeta>(metaFile.readText())
    val apk = computeCachedApkPath(keyDir, jarOrApkFile)
    val currentCTime = retrieveCTime(Paths.get(meta.canonicalPath))
    if (currentCTime == meta.posixCTime) {
      return Pair(apk.toFile(), meta.apkSha256Sum)
    } else {
      return null
    }
  }

  private fun computeCachedApkPath(keyDir: Path, jarOrApkFile: File): Path {
    val canonicalPath = jarOrApkFile.canonicalPath
    val canonicalFile = File(canonicalPath)
    return keyDir.resolve("${canonicalFile.nameWithoutExtension}.apk")
  }

  private fun updateMeta(keyDir: Path, jarOrApkFile: File): String {
    val apk = computeCachedApkPath(keyDir, jarOrApkFile)
    check(apk.exists())

    val canonicalPath = jarOrApkFile.canonicalPath
    val posixCTime = retrieveCTime(Paths.get(canonicalPath))
    val apkSha256Sum = Sha.computeSha256Sum(apk.readBytes())

    val metaFile = keyDir.resolve("meta")
    FileOutputStream(metaFile.toFile()).use {
      Json.encodeToStream(
        ApkCacheMeta(
          canonicalPath = canonicalPath,
          posixCTime = posixCTime,
          apkSha256Sum = apkSha256Sum,
        ),
        it
      )
    }

    return apkSha256Sum
  }

  fun computeKeyDir(jar: File): Path {
    val hash = Sha.computeSha256Sum(jar.canonicalPath)

    return cacheRoot.resolve(hash)
  }

  /** True POSIX ctime via the "unix" attribute view. */
  private fun retrieveCTime(path: Path): Long {
    val t = Files.getAttribute(path, "unix:ctime") as FileTime
    return t.toMillis()
  }
}

@Serializable
data class ApkCacheMeta(
  val canonicalPath: String,
  val posixCTime: Long,
  val apkSha256Sum: String,
)
