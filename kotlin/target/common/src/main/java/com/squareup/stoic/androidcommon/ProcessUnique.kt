package com.squareup.stoic.androidcommon

import com.squareup.stoic.common.PkgStoicPaths
import com.squareup.stoic.common.stoicDeviceDir
import com.squareup.stoic.common.stoicDeviceLockDir
import java.io.File
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

val clkTck: Long by lazy {
  parseAuxvForClkTck() ?: 100
}

// Constructs a process-unique identifier consisting of
// 1. pid
// 2. process-start-time (relative to reboot time)
// 3. boot-id
// The idea is that this can be serialized to disk with no chance of collision between two processes
fun computeProcessUniqueIdentifier(pid: Int): String {
  val bootId = File("/proc/sys/kernel/random/boot_id").readText().removeSuffix("\n")
  val procStartSinceBootMs = parseProcPidStatStartTime(pid) * 1000 / clkTck
  return "$pid-$procStartSinceBootMs-$bootId"
}

fun parseAuxvForClkTck(): Long? {
  val auxvFile = File("/proc/self/auxv")
  if (!auxvFile.exists()) return null

  auxvFile.inputStream().use { stream ->
    val buffer = ByteArray(16)
    while (stream.read(buffer) == 16) {
      val type = java.nio.ByteBuffer.wrap(buffer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
      val value = java.nio.ByteBuffer.wrap(buffer, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
      if (type == 17L) { // AT_CLKTCK
        return value
      } else if (type == 0L) {
        break // End of vector
      }
    }
  }
  return null
}

fun parseProcPidStatStartTime(pid: Int): Long {
  val statLine = File("/proc/$pid/stat").readText()
  val endOfComm = statLine.lastIndexOf(')')
  val afterComm = statLine.substring(endOfComm + 2)
  val fields = afterComm.split(" ")

  val startTimeClkTcks = fields[19].toLong()

  return startTimeClkTcks
}

fun computeSshInfoPath(stoicDir: PkgStoicPaths, pid: Int): String {
  val handshakeDir = stoicDir.handshakeDir
  val unique = computeProcessUniqueIdentifier(pid)
  return "$handshakeDir/ssh-info-$unique.sshinfo"
}

fun computeLockPath(pid: Int): String {
  val unique = computeProcessUniqueIdentifier(pid)
  return "$stoicDeviceLockDir/$unique"
}