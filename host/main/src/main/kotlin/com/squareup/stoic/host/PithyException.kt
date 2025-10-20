package com.squareup.stoic.host

class PithyException(
  val pithyMsg: String?,
  val exitCode: Int = 1,
  e: Exception? = null
) : Exception(
  // Format with indentation so that multiline messages render nicely in stack traces
  """
    ${pithyMsg?.replace("\n", "\n    ") ?: "exitCode=$exitCode"}
  """.replace("\\s*$".toRegex(), ""), e
)