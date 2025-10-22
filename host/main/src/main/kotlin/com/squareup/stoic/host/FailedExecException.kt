package com.squareup.stoic.host

class FailedExecException(val exitCode: Int, msg: String, val errorOutput: String?) :
  Exception(msg)
