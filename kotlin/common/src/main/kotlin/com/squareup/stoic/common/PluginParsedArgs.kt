package com.squareup.stoic.common

class PluginParsedArgs(
  val pluginModule: String,
  val pluginArgs: List<String> = listOf(),
  val pluginEnvVars: Map<String, String> = mapOf(),
)