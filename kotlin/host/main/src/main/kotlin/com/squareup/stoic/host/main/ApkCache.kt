@file:OptIn(ExperimentalSerializationApi::class)

package com.squareup.stoic.host.main

import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.Sha
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.d8pm.d8PreserveManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Cache of APK files.
 *
 * We support input in the form of either APK or JAR files. If input is in JAR form them we need to
 * turn it into an APK first.
 */
object ApkCache {
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
    logInfo { "ApkCache.get failed - regenerating sha/apk" }

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
      d8PreserveManifest(jarFile, apkFile)
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
