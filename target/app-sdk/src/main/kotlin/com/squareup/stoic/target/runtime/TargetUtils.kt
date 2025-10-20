package com.squareup.stoic.target.runtime

import java.io.File

// Marker file that server creates to signal it's ready
fun serverUpFile(pkgStoicDir: File): File {
  // Needs to match the name in stoic-attach
  return pkgStoicDir.resolve("server-up")
}
