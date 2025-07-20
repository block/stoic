package com.squareup.stoic.host.main

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.transformValues
import com.github.ajalt.clikt.parameters.options.versionOption
import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.FailedExecException
import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.PithyException
import com.squareup.stoic.common.PluginClient
import com.squareup.stoic.common.PluginParsedArgs
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.runCommand
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.showStatus
import com.squareup.stoic.common.stdout
import com.squareup.stoic.common.stoicDeviceSyncDir
import com.squareup.stoic.common.waitFor
import com.squareup.stoic.common.withStatus
import java.io.File
import java.io.FileFilter
import java.lang.ProcessBuilder.Redirect
import java.net.Socket
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.system.exitProcess

var isGraal: Boolean = false

lateinit var stoicHostUsrConfigDir: String
lateinit var stoicHostUsrPluginSrcDir: String
lateinit var stoicHostUsrSdkDir: String

lateinit var stoicReleaseDir: String
lateinit var stoicReleaseSyncDir: String
lateinit var stoicDemoPluginsDir: String
var androidSerial: String? = null

val adbSerial: String by lazy {
  androidSerial ?: run {
    val serialFromEnv = System.getenv("ANDROID_SERIAL")
    serialFromEnv
      ?: try {
        ProcessBuilder("adb", "get-serialno").stdout(0)
      } catch (e: FailedExecException) {
        e.errorOutput?.let {
          // This is probably just a message saying either
          //   "error: more than one device/emulator"
          // or
          //   "adb: no devices/emulators found"
          throw PithyException(it)
        } ?: run {
          throw e
        }
      }
  }
}


class Entrypoint : CliktCommand(
  name = "stoic",
) {
  companion object {
    const val DEFAULT_PACKAGE = "com.squareup.stoic.demoapp.withoutsdk"
  }
  init {
    context { allowInterspersedArgs = false }
    versionOption(
      version = StoicProperties.STOIC_VERSION_NAME,
      names = setOf("-v", "--version")
    )
  }

  override val printHelpOnEmptyArgs = true
  override fun help(context: Context): String {
    return """
      Stoic communicates with processes running on Android devices.

      Stoic will attempt to attach to a process via jvmti and establish a unix-domain-socket server.
      If successful, stoic will communicate to the server to request that the specified plugin run,
      with any arguments that follow, connecting stdin/stdout/stderr between the plugin and the
      stoic process.

      e.g. stoic helloworld "this is one arg" "this is a second arg"

      Special functionality is available via `stoic --tool <tool-name> <tool-args>` - for details,
      see `stoic --list --tool`
    """.trimIndent()
  }

  // Track which options were explicitly set
  private val specifiedOptions = mutableSetOf<String>()

  fun verifyOptions(subcommand: String, allowedOptions: List<String>) {
    specifiedOptions.forEach {
      if (it !in allowedOptions) {
        throw UsageError("$it not allowed with $subcommand")
      }
    }
  }

  fun RawOption.trackableFlag(): OptionDelegate<Boolean> {
    val longestName = names.maxByOrNull { it.length }!!
    return nullableFlag()
      .transformValues(0..0) {
        specifiedOptions += longestName
        true
      }
      .default(false)
  }

  fun RawOption.trackableOption(): OptionWithValues<String?, String, String> {
    val longestName = names.maxByOrNull { it.length }!!
    return convert {
      specifiedOptions += longestName
      it
    }
  }

  val verbose by option(
    "--verbose",
    help = "enable verbose logging"
  ).trackableFlag()
  val debug by option(
    "--debug",
    help = "enable debug logging"
  ).trackableFlag()
  val info by option(
    "--info",
    help = "enable info logging"
  ).trackableFlag()
  val noStatus by option(
    "--no-status",
    help = "disable status messages"
  ).trackableFlag()

  val restartApp by option(
    "--restart",
    "--restart-app",
    "-r",
    help = "if it's already running, force restart the specified package"
  ).trackableFlag()
  val noStartIfNeeded by option(
    "--no-start-if-needed",
    help = """
      by default, stoic will start the specified package if it's not already running - this option
      disables that behavior
    """.trimIndent()
  ).trackableFlag()

  val androidSerialArg by option(
    "--android-serial",
    "--serial",
    "-s",
    help = "Use device with given serial (overrides \$ANDROID_SERIAL)"
  ).trackableOption()

  val pkg by option(
    "--package",
    "--pkg",
    "-n",
    help = "Specify the package of the process to connect to"
  ).trackableOption().default(DEFAULT_PACKAGE)

  // TODO: support --pid/-p to allow attaching by pid

  val env by option(
    "--env",
    help = "Environment key=value pairs - plugins access these via stoic.getenv(...)"
  ).trackableOption().pair().multiple()

  val isDemo by option(
    "--demo",
    help = "limit plugin resolution to demo plugins"
  ).trackableFlag()
  val isBuiltin by option(
    "--builtin",
    "--built-in",
    help = "limit plugin resolution to builtin plugins"
  ).trackableFlag()
  val isUser by option(
    "--user",
    "--usr",
    help = "limit plugin resolution to user plugins"
  ).trackableFlag()
  val isTool by option(
    "--tool",
    "-t",
    help = "run a tool - see `stoic --tool --list` for details"
  ).flag()
  val isList by option(
    "--list",
    "-l",
    help = "list plugins (pass --tool to list tools)"
  ).flag()

  val subcommand by argument(name = "plugin").optional()
  val subcommandArgs by argument(name = "plugin-args").multiple()

  var demoAllowed = false
  var builtinAllowed = false
  var userAllowed = false

  fun resolveAllowed() {
    val count = listOf(isDemo, isBuiltin, isUser).count { it }
    if (count == 0) {
      demoAllowed = true
      builtinAllowed = true
      userAllowed = true
    } else if (count > 1) {
      throw UsageError("--demo/--builtin/--user are mutually exclusive")
    } else if (isTool) {
      throw UsageError("--tool is invalid with --demo/--builtin/--user")
    } else if (isDemo) {
      demoAllowed = true
    } else if (isBuiltin) {
      builtinAllowed = true
    } else if (isUser) {
      userAllowed = true
    }
  }

  override fun run() {
    resolveAllowed()

    // We need to store this globally since we use it to resolve which device we connect to
    androidSerial = androidSerialArg

    if (restartApp && noStartIfNeeded) {
      throw CliktError("--restart-app and --no-start-if-needed are mutually exclusive")
    }

    if (listOf(verbose, debug, info).count { it } > 1) {
      throw CliktError("--verbose and --debug are mutually exclusive")
    }

    minLogLevel = if (verbose) {
      LogLevel.VERBOSE
    } else if (debug) {
      LogLevel.DEBUG
    } else if (info) {
      LogLevel.INFO
    } else {
      LogLevel.WARN
    }
    showStatus = !noStatus

    logDebug { "isGraal=$isGraal" }

    val exitCode = runPluginOrTool(this)
    if (exitCode != 0) {
      throw PithyException(null, exitCode)
    }
  }
}

class ShellCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic shell") {
  init { context { allowInterspersedArgs = false } }
  override val treatUnknownOptionsAsArgs = true
  override fun help(context: Context): String {
    return """
      Stoic used to provide a shell command, but its unrelated to Stoic's core functionality, so
      it has been removed.
    """.trimIndent()
  }
  val shellArgs by argument().multiple()
  override fun run() {
    echoFormattedHelp()
    throw CliktError()
  }
}

class RsyncCommand(val entrypoint: Entrypoint) : CoreCliktCommand(name = "rsync") {
  init {
    context {
      allowInterspersedArgs = false
    }
  }
  override val treatUnknownOptionsAsArgs = true
  override fun help(context: Context): String {
    return """
      Stoic used to provide an rsync command, but its unrelated to Stoic's core functionality, so
      it has been removed.
    """.trimIndent()
  }

  val rsyncArgs by argument().multiple()
  override fun run() {
    echoFormattedHelp()
    throw CliktError()
  }
}

class InitConfigCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic init-config") {
  init {
    context {
      allowInterspersedArgs = false
    }
  }
  override val treatUnknownOptionsAsArgs = true
  override fun help(context: Context): String {
    return """
      Stoic used to provide an init-config command, but it's not longer necessary, so it has been
      removed.
    """.trimIndent()
  }

  val initConfigArgsArgs by argument().multiple()
  override fun run() {
    echoFormattedHelp()
    throw CliktError()
  }
}

class PluginCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic plugin") {
  override fun help(context: Context): String {
    return """
      Create a new plugin
    """.trimIndent()
  }

  val isNew by option("--new", "-n").flag()
  val isEdit by option("--edit", "-e").flag()

  // TODO: support `stoic plugin --list`

  val pluginName by argument("name")

  override fun run() {
    entrypoint.verifyOptions("plugin", listOf("--verbose", "--debug"))

    // Ensure the SDK is up-to-date
    syncSdk()

    if (isNew) {
      val usrPluginSrcDir = newPlugin(pluginName)
      System.err.println("New plugin src written to $usrPluginSrcDir")
      System.err.println("Run it with: stoic $pluginName")
    }

    if (isEdit) {
      val usrPluginSrcDir = File("$stoicHostUsrPluginSrcDir/$pluginName")
      if (!usrPluginSrcDir.exists()) {
        throw PithyException("$usrPluginSrcDir does not exist")
      }

      val stoicEditor = System.getenv("STOIC_EDITOR")
      val editor = System.getenv("EDITOR")
      val resolvedEditor = if (!stoicEditor.isNullOrBlank()) {
        stoicEditor
      } else if (!editor.isNullOrBlank()) {
        editor
      } else {
        throw PithyException("""
          Editor not found. Please export STOIC_EDITOR or EDITOR.
          For Android Studio:
            export STOIC_EDITOR='open -a "Android Studio"'
          Or, you can open your editor to $usrPluginSrcDir manually.
        """.trimIndent())
      }

      val editorParts = if (resolvedEditor.contains(" ")) {
        ProcessBuilder("bash", "-c", "for x in $resolvedEditor; do printf '%s\\n' \"\$x\"; done")
          .stdout()
          .split('\n')
      } else {
        listOf(resolvedEditor)
      }

      val srcMain = "$usrPluginSrcDir/src/main/kotlin/Main.kt"
      val srcGradle = "$usrPluginSrcDir/build.gradle.kts"
      ProcessBuilder(editorParts + listOf(srcMain, srcGradle))
        .inheritIO()
        .waitFor(0)
    }
  }
}

fun newPlugin(pluginName: String, ignoreExisting: Boolean = false): File {
  val pluginNameRegex = Regex("^[A-Za-z0-9_-]+$")
  if (!pluginName.matches(pluginNameRegex)) {
    throw PithyException("Plugin name must adhere to regex: ${pluginNameRegex.pattern}")
  }

  // Ensure the parent directory has been created
  File(stoicHostUsrPluginSrcDir).mkdirs()

  val usrPluginSrcDir = File("$stoicHostUsrPluginSrcDir/$pluginName")
  val pluginTemplateSrcDir = File("$stoicReleaseDir/template/plugin-template")

  if (usrPluginSrcDir.exists()) {
    if (ignoreExisting) {
      return usrPluginSrcDir
    } else {
      throw PithyException("$usrPluginSrcDir already exists")
    }
  }

  // Copy the scratch template plugin
  ProcessBuilder(
    "cp",
    "-iR",
    pluginTemplateSrcDir.absolutePath,
    usrPluginSrcDir.absolutePath
  ).inheritIO().redirectInput(File("/dev/null")).waitFor(0)

  File(usrPluginSrcDir, ".stoic_template_version").writeText(StoicProperties.STOIC_VERSION_NAME)

  return usrPluginSrcDir
}

// This will go away once we publish the jars to maven
fun syncSdk() {
  // Ensure the parent directory has been created
  File(stoicHostUsrPluginSrcDir).mkdirs()
  ProcessBuilder("rsync", "--archive", "$stoicReleaseDir/sdk/", "$stoicHostUsrSdkDir/")
    .inheritIO()
    .waitFor(0)
}


fun main(rawArgs: Array<String>) {
  isGraal = System.getProperty("org.graalvm.nativeimage.imagecode") != null
  stoicReleaseDir = if (isGraal) {
    // This is how we find the release dir from the GraalVM-generated binary
    val pathToSelf = ProcessHandle.current().info().command().get()
    Paths.get(pathToSelf).toRealPath().parent.parent.toAbsolutePath().toString()
  } else {
    // This is how we find the release dir when normally (via a jar)
    val uri = Entrypoint::class.java.protectionDomain.codeSource.location.toURI()
    File(uri).toPath().parent.parent.toAbsolutePath().toString()
  }

  minLogLevel = LogLevel.WARN

  stoicReleaseSyncDir = "$stoicReleaseDir/sync"
  stoicDemoPluginsDir = "$stoicReleaseDir/demo-plugins"

  stoicHostUsrConfigDir = System.getenv("STOIC_CONFIG").let {
    if (it.isNullOrBlank()) { "${System.getenv("HOME")}/.config/stoic" } else { it }
  }
  stoicHostUsrPluginSrcDir = "$stoicHostUsrConfigDir/plugin"
  stoicHostUsrSdkDir = "$stoicHostUsrConfigDir/sdk"

  try {
    Entrypoint().main(rawArgs)
    exitProcess(0)
  } catch (e: PithyException) {
    // If we have a pithy message to display to the user, we'll display just that message
    // (unless debug logging is enabled) and then exit with status code 1.
    logDebug { e.stackTraceToString() }
    if (e.pithyMsg != null) {
      System.err.println(e.pithyMsg)
    } else {
      logDebug { "(no pithyMsg)" }
    }
    exitProcess(e.exitCode)
  } catch (e: Exception) {
    // We don't have a pithy message
    logError { e.stackTraceToString() }
    exitProcess(1)
  }
}

fun runList(entrypoint: Entrypoint): Int {
  entrypoint.verifyOptions("--list", listOf())
  if (entrypoint.subcommand != null) {
    throw UsageError("`stoic --list` doesn't take positional arguments")
  } else if (entrypoint.isTool) {
    // TODO: deduplicate this list with the one in runTool
    listOf("plugin").forEach {
      println(it)
    }
  } else {
    val pluginList = gatherPluginList(entrypoint)
    pluginList.forEach {
      println(it)
    }
  }

  return 0
}

fun gatherPluginList(entrypoint: Entrypoint): List<String> {
  // TODO: Invoke builtin stoic-list to get list of builtin plugins (and allow --package)
  val pluginList = mutableListOf<String>()

  val dexJarFilter = object : FileFilter {
    override fun accept(file: File): Boolean {
      return file.isFile && file.name.endsWith(".dex.jar")
    }
  }

  entrypoint.resolveAllowed()

  if (entrypoint.userAllowed) {
    val usrSourceDirs = File(stoicHostUsrPluginSrcDir).listFiles()!!
    usrSourceDirs.forEach {
      if (!it.name.startsWith(".")) {
        pluginList.add("${it.name} (--user)")
      }
    }
  }

  if (entrypoint.demoAllowed) {
    val demoPrebuilts = File(stoicDemoPluginsDir).listFiles(dexJarFilter)!!
    demoPrebuilts.forEach {
      pluginList.add("${it.name.removeSuffix(".dex.jar")} (--demo)")
    }
  }

  return pluginList
}

fun runTool(entrypoint: Entrypoint): Int {
  val toolName = entrypoint.subcommand
  val command = when (toolName) {
    "shell" -> ShellCommand(entrypoint)
    "rsync" -> RsyncCommand(entrypoint)
    "init-config" -> InitConfigCommand(entrypoint)
    "plugin" -> PluginCommand(entrypoint)
    "help" -> {
      entrypoint.echoFormattedHelp()
      return 0
    }
    "version" -> {
      entrypoint.echo("stoic version ${StoicProperties.STOIC_VERSION_NAME}")
      return 0
    }
    else -> {
      if (entrypoint.isTool) {
        // The user was definitely trying to run a tool
        throw PithyException("tool `$toolName` not found, to see list: stoic --tool --list")
      } else {
        // Maybe the user was trying to run a plugin
        throw PithyException("""
          plugin or tool `$toolName` not found, to see list:
          stoic --tool --list (for tools)
          stoic --list (for plugins)
          """.trimIndent())
      }
    }
  }

  command.main(entrypoint.subcommandArgs)
  return 0
}

fun runPluginFastPath(entrypoint: Entrypoint, dexJarInfo: Pair<File, String>?): Int {
  // `adb forward`-powered fast path
  val serverSocketName = serverSocketName(entrypoint.pkg)
  val portStr = adbProcessBuilder(
    "forward", "tcp:0", "localabstract:$serverSocketName"
  ).stdout()
  try {
    Socket("localhost", portStr.toInt()).use {
      val pluginParsedArgs = PluginParsedArgs(
        pkg = entrypoint.pkg,
        restartApp = entrypoint.restartApp,
        startIfNeeded = !entrypoint.noStartIfNeeded,
        pluginModule = entrypoint.subcommand!!,
        pluginArgs = entrypoint.subcommandArgs,
        pluginEnvVars = entrypoint.env.toMap(),
      )
      val client = PluginClient(dexJarInfo, pluginParsedArgs, it.inputStream, it.outputStream)
      return client.handle()
    }
  } finally {
    adbProcessBuilder("forward", "--remove", portStr)
  }
}

fun runPluginOrTool(entrypoint: Entrypoint): Int {
  if (entrypoint.isList) {
    return runList(entrypoint)
  } else if (entrypoint.isTool) {
    return runTool(entrypoint)
  }

  // If resolvePluginModule returns null then we'll try assuming its a builtin (if we resolved the
  // device) if that fails, then we'll check for a tool
  val dexJarInfo = resolveUserOrDemo(entrypoint)

  val isPlugin = if (dexJarInfo != null || entrypoint.isBuiltin) {
    true
  } else if (entrypoint.pkg != Entrypoint.DEFAULT_PACKAGE) {
    // Tools don't take pkg as argument, so it must be a plugin
    true
  } else if (entrypoint.commandName in listOf("stoic-list", "stoic-status", "stoic-noop")) {
    // We know the list of plugins that the default package supports - if the command is on that
    // list, then it's a plugin
    // TODO: validate this list is sync'd with the actual supported plugins
    true
  } else {
    // Otherwise it's not a plugin
    false
  }

  return if (isPlugin) {
    runPlugin(entrypoint, dexJarInfo)
  } else {
    runTool(entrypoint)
  }
}

fun runPlugin(entrypoint: Entrypoint, dexJarInfo: Pair<File, String>?): Int {
  if (!entrypoint.restartApp) {
    try {
      return runPluginFastPath(entrypoint, dexJarInfo)
    } catch (e: PithyException) {
      // PithyException will be caught at the outermost level
      throw e
    } catch (e: Exception) {
      logInfo { "fast-path failed" }
      logDebug { e.stackTraceToString() }
    }
  }

  withStatus("Attaching...") {
    // force start the server, and then retry
    logInfo { "starting server via slow-path" }

    // syncDevice is usually not necessary - we could optimistically assume its not and have
    // stoic-attach verify. But it typically takes less than 50ms - that's well under 5% of the time
    // needed for the slow path - so it's not too bad.
    syncDevice()

    val startOption = if (entrypoint.restartApp) {
      "restart"
    } else if (entrypoint.noStartIfNeeded) {
      "do_not_start"
    } else {
      "start_if_needed"
    }

    val debugOption = if (minLogLevel <= LogLevel.DEBUG) {
      "debug_true"
    } else {
      "debug_false"
    }

    val proc = adbProcessBuilder(
      "shell",
      shellEscapeCmd(
        listOf(
          "$stoicDeviceSyncDir/bin/stoic-attach",
          "$STOIC_PROTOCOL_VERSION",
          entrypoint.pkg,
          startOption,
          debugOption
        )
      )
    )
      .redirectInput(File("/dev/null"))
      .redirectOutput(Redirect.PIPE)
      .redirectErrorStream(true)
      .start()

    if (minLogLevel <= LogLevel.DEBUG) {
      proc.inputReader().use { inputReader ->
        thread {
          inputReader.lineSequence().forEach {
            logDebug { it }
          }
        }
      }
      if (proc.waitFor() != 0) {
        throw PithyException("stoic-attach failed - see output above")
      }
    } else {
      if (proc.waitFor() != 0) {
        val stoicAttachOutput = proc.inputReader().readText()
        throw PithyException("stoic-attach failed\n$stoicAttachOutput")
      }
    }
  }

  logInfo { "retrying fast-path" }
  return runPluginFastPath(entrypoint, dexJarInfo)
}

fun checkRequiredSdkPackages(vararg required: String) {
  runCommand(
    listOf("$stoicReleaseDir/script/check_required_sdk_packages.sh") + required,
    inheritIO = true
  )
}

fun syncDevice() {
  logBlock(LogLevel.INFO, { "syncing device" }) {
    // The /. prevents creating an additional level of nesting when the destination directory
    // already exists.
    check(adbProcessBuilder(
      "push",
      "--sync",
      "$stoicReleaseSyncDir/.",
      stoicDeviceSyncDir,
    ).start().waitFor() == 0)
  }
}

fun shellEscapeCmd(cmdArgs: List<String>): String {
  return if (cmdArgs.isEmpty()) {
    ""
  } else {
    return ProcessBuilder(listOf("bash", "-c", """ printf " %q" "$@" """, "stoic") + cmdArgs).stdout().drop(0)
  }
}

fun resolveUserOrDemo(entrypoint: Entrypoint): Pair<File, String>? {
  val pluginName = entrypoint.subcommand!!
  logDebug { "Attempting to resolve '$pluginName'" }
  if (listOf(entrypoint.isDemo, entrypoint.isBuiltin, entrypoint.isUser).count { it } > 1) {
    throw PithyException("At most one of --demo/--builtin/--user may be specified")
  }

  if (pluginName.endsWith(".jar")) {
    if (!entrypoint.userAllowed) {
      throw PithyException("jar plugin are considered user - --demo/--builtin options are incompatible")
    }

    val file = File(pluginName)
    if (!file.exists()) {
      throw PithyException("File not found: $pluginName")
    }

    return DexJarCache.resolve(file)
  }

  val pluginDexJar = "$pluginName.dex.jar"
  if (entrypoint.userAllowed) {
    val usrPluginSrcDir = "$stoicHostUsrPluginSrcDir/$pluginName"
    if (File(usrPluginSrcDir).exists()) {
      // Ensure the SDK is up-to-date
      syncSdk()

      withStatus("Compiling...") {
        val outputPath = logBlock(LogLevel.INFO, { "building $usrPluginSrcDir" }) {
          logInfo { "building plugin" }
          val prefix = "STOIC_BUILD_PLUGIN_OUT="
          try {
            ProcessBuilder("./stoic-build-plugin")
              .inheritIO()
              .directory(File(usrPluginSrcDir))
              .stdout()
              .lineSequence()
              .first { it.startsWith(prefix) }
              .removePrefix(prefix)
          } catch (e: NoSuchElementException) {
            logDebug { e.stackTraceToString() }
            throw PithyException("stoic-build-plugin must output line: $prefix<path-to-output-jar-or-apk>")
          }
        }

        return DexJarCache.resolve(File(outputPath))
      }
    }
  }

  if (entrypoint.demoAllowed) {
    val corePluginDexJar = File("$stoicDemoPluginsDir/$pluginDexJar")
    if (corePluginDexJar.exists()) {
      return DexJarCache.resolve(corePluginDexJar)
    } else if (entrypoint.isDemo) {
      throw PithyException("Demo plugin `$pluginName` not found, to see list: stoic --list --demo")
    }
  }

  return null
}

fun adbProcessBuilder(vararg args: String): ProcessBuilder {
  val procArgs = listOf("adb", "-s", adbSerial) + args
  logDebug { "adbProcBuilder($procArgs)" }
  return ProcessBuilder(procArgs)
}
