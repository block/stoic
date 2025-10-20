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
import com.github.ajalt.clikt.parameters.types.enum
import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.host.AttachVia
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.common.logWarn
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.showStatus
import com.squareup.stoic.common.withStatus
import com.squareup.stoic.host.FailedExecException
import com.squareup.stoic.host.FileWithSha
import com.squareup.stoic.host.MismatchedVersionException
import com.squareup.stoic.host.PithyException
import com.squareup.stoic.host.PluginHost
import com.squareup.stoic.host.PluginParsedArgs
import com.squareup.stoic.host.runCommand
import com.squareup.stoic.host.stoicDeviceSyncDir
import com.squareup.stoic.host.stdout
import com.squareup.stoic.host.waitFor
import java.io.EOFException
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
    const val DEFAULT_DEBUGGABLE_PACKAGE = "com.squareup.stoic.demoapp.withoutsdk"
    const val DEFAULT_NON_DEBUGGABLE_PACKAGE = "com.squareup.stoic.demoapp.withsdk"
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

  val rawPkg by option(
    "--package",
    "--pkg",
    "-n",
    help = "Specify the package of the process to connect to"
  ).trackableOption()

  val pkg by lazy {
    rawPkg ?: when (attachVia) {
      AttachVia.JVMTI -> DEFAULT_DEBUGGABLE_PACKAGE
      AttachVia.JVMTI_ROOT -> DEFAULT_NON_DEBUGGABLE_PACKAGE
      AttachVia.SDK -> DEFAULT_NON_DEBUGGABLE_PACKAGE
    }
  }

  val attachVia by option(
    "--attach-via",
    "-a",
    help = "Specify the method to attach to the target process"
  ).trackableOption().enum<AttachVia>().default(AttachVia.JVMTI)

  // TODO: support --pid/-p to allow attaching by pid

  val env by option(
    "--env",
    help = "Environment key=value pairs - plugins access these via stoic.getenv(...)"
  ).trackableOption().convert { value ->
    val parts = value.split('=', limit = 2)
    if (parts.size != 2) {
      fail("--env must be in format KEY=VALUE")
    }
    parts[0] to parts[1]
  }.multiple()

  val isDemo by option(
    "--demo",
    help = "limit plugin resolution to demo plugins"
  ).trackableFlag()
  val isEmbedded by option(
    "--embedded",
    help = "limit plugin resolution to embedded plugins"
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
  var embeddedAllowed = false
  var userAllowed = false

  fun resolveAllowed() {
    val count = listOf(isDemo, isEmbedded, isUser).count { it }
    if (count == 0) {
      demoAllowed = true
      embeddedAllowed = true
      userAllowed = true
    } else if (count > 1) {
      throw UsageError("--demo/--embedded/--user are mutually exclusive")
    } else if (isTool) {
      throw UsageError("--tool is invalid with --demo/--embedded/--user")
    } else if (isDemo) {
      demoAllowed = true
    } else if (isEmbedded) {
      embeddedAllowed = true
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
    // The graalvm-binary will be in bin/darwin-arm64/stoic, so we need to walk up three parents.
    val pathToSelf = ProcessHandle.current().info().command().get()
    Paths.get(pathToSelf).toRealPath().parent.parent.parent.toAbsolutePath().toString()
  } else {
    // This is how we find the release dir when normally (via a jar)
    // The jar file will be in jar/stoic-host-main.jar, so we need to walk up two parents.
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
  entrypoint.verifyOptions("--list", listOf("--package", "--pkg", "--attach-via"))
  if (entrypoint.subcommand != null) {
    throw UsageError("`stoic --list` doesn't take positional arguments")
  } else if (entrypoint.isTool) {
    // TODO: deduplicate this list with the one in runTool
    listOf("plugin").forEach {
      println(it)
    }
  } else {
    // Show user and demo plugins
    val pluginList = gatherPluginList(entrypoint).toMutableList()

    val pluginParsedArgs = PluginParsedArgs(
      pluginModule = "__stoic-list",
      pluginArgs = emptyList(),
      pluginEnvVars = emptyMap()
    )

    // Capture output from stoic-list - we need to temporarily redirect System.out
    // TODO: Provide an API for running a plugin that allows overriding stdin/stdout/stderr directly
    val originalOut = System.out
    val capturedOutput = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(capturedOutput))
    try {
      val exitCode = runPluginFastPath(
        pkg = entrypoint.pkg,
        pluginParsedArgs = pluginParsedArgs,
        apkInfo = null
      )

      if (exitCode == 0) {
        // Add embedded plugins to the list (filtering out internal plugins)
        capturedOutput.toString().trim().lines().forEach { pluginName ->
          if (pluginName.isNotBlank() && !isInternalPluginName(pluginName)) {
            pluginList.add("$pluginName (--embedded)")
          }
        }
      }
    } finally {
      System.setOut(originalOut)
    }

    pluginList.forEach {
      println(it)
    }
  }

  return 0
}

fun isInternalPluginName(name: String): Boolean {
  return name.startsWith("__stoic-")
}

fun gatherPluginList(entrypoint: Entrypoint): List<String> {
  val pluginList = mutableListOf<String>()

  val apkFilter = object : FileFilter {
    override fun accept(file: File): Boolean {
      return file.isFile && file.name.endsWith(".apk")
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
    val demoPrebuilts = File(stoicDemoPluginsDir).listFiles(apkFilter)!!
    demoPrebuilts.forEach {
      pluginList.add("${it.name.removeSuffix(".apk")} (--demo)")
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

fun runPluginFastPath(entrypoint: Entrypoint, apkInfo: FileWithSha?): Int {
  return runPluginFastPath(
    pkg = entrypoint.pkg,
    pluginParsedArgs = PluginParsedArgs(
      pluginModule = entrypoint.subcommand!!,
      pluginArgs = entrypoint.subcommandArgs,
      pluginEnvVars = entrypoint.env.toMap(),
    ),
    apkInfo = apkInfo
  )
}

fun runPluginFastPath(
  pkg: String,
  pluginParsedArgs: PluginParsedArgs,
  apkInfo: FileWithSha?
): Int {
  // `adb forward`-powered fast path
  val serverSocketName = serverSocketName(pkg)
  val portStr = adbProcessBuilder(
    "forward", "tcp:0", "localabstract:$serverSocketName"
  ).stdout()
  try {
    Socket("localhost", portStr.toInt()).use {
      val host = PluginHost(apkInfo, pluginParsedArgs, it.inputStream, it.outputStream)
      return host.handle()
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

  // If resolvePluginModule returns null then we'll try assuming its a embedded
  // (if we resolved the device) if that fails, then we'll check for a tool
  val apkInfo = resolveUserOrDemo(entrypoint)

  // We have somewhat complex logic for determining whether the specified command is a plugin or
  // a tool. Treating tools like plugins allows us to have separate args for them.
  //   e.g. In `stoic plugin --create foo`, `--create` is specific to the `plugin` tool.
  // But we want to avoid the problem of people creating their own plugins that conflict with tool
  // names. The problem could get even worse if we add a new tool in the future that conflicts with
  // a plugin others have been using for a long time.
  // To avoid such problems, we have the rule that an invocation can only resolve to a tool if the
  // package is not specified. We control what plugins are present in the default package so we can
  // prevent conflicts.
  val usingDefaultPackage = entrypoint.rawPkg == null
  val isPlugin = if (apkInfo != null || entrypoint.isEmbedded) {
    true
  } else if (entrypoint.isTool) {
    // Explicitly requested a tool
    false
  } else if (!usingDefaultPackage) {
    // Tools are only valid with the default package, so this must be a plugin
    true
  } else {
    // The default package only has no embedded plugins other than the internal ones - if it's not
    // one of these then it must be a tool.
    entrypoint.subcommand?.let { isInternalPluginName(it) } ?: false
  }

  return if (isPlugin) {
    try {
      runPlugin(entrypoint, apkInfo)
    } catch (e: MismatchedVersionException) {
      if (entrypoint.pkg == Entrypoint.DEFAULT_NON_DEBUGGABLE_PACKAGE) {
        // The non-debuggable package contains the Stoic SDK. If we have an old version installed it
        // could cause a problem. We can automatically try to fix it by uninstalling and retrying.
        logWarn { "runPlugin threw ${e.stackTraceToString()} - uninstalling and retrying" }

        // We ignore the exit code since the packages might not actually be installed
        adbProcessBuilder("uninstall", Entrypoint.DEFAULT_NON_DEBUGGABLE_PACKAGE).waitFor(null)

        runPlugin(entrypoint, apkInfo)
      } else if (entrypoint.attachVia == AttachVia.SDK) {
        throw Exception(
          "Protocol version mismatch - please rebuild the app with sdk version: ${StoicProperties.STOIC_VERSION_NAME}",
          e
        )
      } else {
        """
          Protocol version mismatch - please try restarting the app (you can use the --restart flag)
          to force Stoic to re-attach.
        """.trimIndent()
        throw e
      }
    }
  } else {
    runTool(entrypoint)
  }
}

fun runPlugin(entrypoint: Entrypoint, apkInfo: FileWithSha?): Int {
  if (!entrypoint.restartApp) {
    try {
      return runPluginFastPath(entrypoint, apkInfo)
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
          debugOption,
          entrypoint.attachVia.str
        )
      )
    )
      .redirectInput(File("/dev/null"))
      .redirectOutput(Redirect.PIPE)
      .redirectErrorStream(true)
      .start()

    val maybeError = if (minLogLevel <= LogLevel.DEBUG) {
      val stoicAttachOutputLines = mutableListOf<String>()
      thread {
        proc.inputReader().use { inputReader ->
          inputReader.lineSequence().forEach {
            logDebug { it }
            stoicAttachOutputLines += it
          }
        }
      }
      if (proc.waitFor() != 0) {
        stoicAttachOutputLines.joinToString("\n")
      } else {
        null
      }
    } else {
      if (proc.waitFor() != 0) {
        proc.inputReader().readText()
      } else {
        null
      }
    }

    if (maybeError != null) {
      if (entrypoint.attachVia == AttachVia.JVMTI_ROOT) {
        if (maybeError.contains("Can't attach agent, process is not debuggable")) {
          throw PithyException("""
            ${entrypoint.pkg} appears to not have JDWP enabled

            To fix this you can run the following (may need to set ANDROID_SERIAL appropriately):
              adb shell su 0 setprop persist.debug.dalvik.vm.jdwp.enabled '"1"' && adb shell su 0 stop && adb shell su 0 start && sleep 3
            (sleep is necessary to allow system processes to finish restarting):

            NOTE: this will enable jdwp for all apps on the device.
          """.trimIndent())
        }
      }
      throw PithyException("stoic-attach failed:\n$maybeError")
    }

    logInfo { "waiting for up to 3 seconds for the port to be up" }
    val startTime = System.nanoTime()
    while ((System.nanoTime() - startTime) < 3_000_000_000) {
      try {
        runPluginFastPath(
          entrypoint.pkg,
          PluginParsedArgs("__stoic-noop"),
          null,
        )
        break
      } catch (e: EOFException) {
        logInfo { "server not up yet: ${e.stackTraceToString()}" }
        logInfo { "Sleeping 100ms and retrying..." }
        Thread.sleep(100)
      }
    }
  }


  logInfo { "server up - retrying fast-path" }
  return runPluginFastPath(entrypoint, apkInfo)
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

fun resolveUserOrDemo(entrypoint: Entrypoint): FileWithSha? {
  val pluginName = entrypoint.subcommand!!
  logDebug { "Attempting to resolve '$pluginName'" }
  if (listOf(entrypoint.isDemo, entrypoint.isEmbedded, entrypoint.isUser).count { it } > 1) {
    throw PithyException("At most one of --demo/--embedded/--user may be specified")
  }

  if (pluginName.endsWith(".jar") || pluginName.endsWith(".apk")) {
    if (!entrypoint.userAllowed) {
      val fileType = if (pluginName.endsWith(".jar")) "jar" else "apk"
      throw PithyException("$fileType plugin are considered user - --demo/--embedded options are incompatible")
    }

    val file = File(pluginName)
    if (!file.exists()) {
      throw PithyException("File not found: $pluginName")
    }

    return ApkCache.resolve(file)
  }

  val pluginApk = "$pluginName.apk"
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

        return ApkCache.resolve(File(outputPath))
      }
    } else if (entrypoint.isUser) {
      throw PithyException("User plugin `$pluginName` not found, to see list: stoic --list --user")
    }
  }

  if (entrypoint.demoAllowed) {
    val corePluginApk = File("$stoicDemoPluginsDir/$pluginApk")
    if (corePluginApk.exists()) {
      return ApkCache.resolve(corePluginApk)
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
