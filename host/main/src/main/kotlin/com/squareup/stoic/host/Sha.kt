package com.squareup.stoic.host

import java.security.MessageDigest

object Sha {
  fun computeSha256Sum(text: String): String = computeSha256Sum(text.toByteArray())

  fun computeSha256Sum(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
