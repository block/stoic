package com.squareup.stoic.common

class FailedExecException(val exitCode: Int, msg: String, val errorOutput: String?): Exception(msg)