package com.squareup.stoic.target.runtime

interface StoicNamedPlugin {
  fun run(args: List<String>): Int
}