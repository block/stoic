package com.squareup.stoic.jvmti

const val TYPE_VOID = 'V'
const val TYPE_BOOLEAN = 'Z'
const val TYPE_BYTE = 'B'
const val TYPE_SHORT = 'S'
const val TYPE_INT = 'I'
const val TYPE_LONG = 'J'
const val TYPE_FLOAT = 'F'
const val TYPE_DOUBLE = 'D'
const val TYPE_OBJECT = 'L'

class BreakpointResult(val forceEarlyReturn: Any, val type: Char)