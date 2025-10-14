package com.squareup.stoic.preparerelease

import com.squareup.stoic.bridge.versionCodeFromVersionName
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    System.err.println("Usage: prepareRelease <path-to-stoic-root>")
    exitProcess(1)
  }

  val stoicDir = File(File(args[0]).absolutePath)
  println("stoicDir: $stoicDir")

  val versionFile = stoicDir.resolve("prebuilt/STOIC_VERSION")
  val currentVersion = versionFile.readText().trim()
  validateSemver(currentVersion)

  val releaseVersion = currentVersion.removeSuffix("-SNAPSHOT")
  require(versionCodeFromVersionName(releaseVersion) == versionCodeFromVersionName(currentVersion) + 1)

  val postReleaseVersion = incrementSemver(releaseVersion)
  require(versionCodeFromVersionName(postReleaseVersion) == versionCodeFromVersionName(releaseVersion) + 9)

  ensureCleanGitRepo(stoicDir)
  val artifactsDir = stoicDir.resolve("releases/$releaseVersion")

  // Clean up any previous attempt
  if (artifactsDir.exists()) {
    println("Resuming from previous attempt: $artifactsDir")
  } else {
    println("Creating artifacts directory: $artifactsDir")
    artifactsDir.mkdirs()
  }

  // state tracking
  val stateFile = artifactsDir.resolve(".prepare_state")
  fun markStep(step: String) {
    println("Completed: $step")
    stateFile.writeText(step)
  }
  fun lastStep(): String? = if (stateFile.exists()) stateFile.readText().trim() else null
  fun shouldRun(step: String): Boolean {
    val done = lastStep()
    require(stepOrder.indexOf(step) != -1)
    require(done == null ||stepOrder.indexOf(done) != -1)
    return done == null || stepOrder.indexOf(step) > stepOrder.indexOf(done)
  }
  fun step(name: String, action: () -> Unit) {
    if (shouldRun(name)) {
      println("Running step: $name")
      action()
      markStep(name)
    } else {
      println("Skipping already completed step: $name")
    }
  }

  lastStep()?.let { println("Resuming from step: $it") }

  step("shellcheck") {
    check(runCommand(listOf("test/shellcheck.sh"), stoicDir))
  }

  val releaseBranch = "release/$releaseVersion"
  step("create-branch") {
    val branch = runCommandOutput(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), stoicDir).trim()
    require(branch == "main") { "Must run from main branch (currently on '$branch')" }

    require(currentVersion.endsWith("-SNAPSHOT")) {
      "Current version must end in -SNAPSHOT (was '$currentVersion')"
    }

    println("Creating release branch: $releaseBranch")
    check(runCommand(listOf("git", "checkout", "-b", releaseBranch), stoicDir))
    versionFile.writeText("$releaseVersion\n")
    check(runCommand(listOf("git", "add", versionFile.path), stoicDir))
    check(runCommand(listOf("git", "commit", "-m", "Prepare $releaseVersion release"), stoicDir))
    check(runCommand(listOf("git", "push", "--set-upstream", "origin", releaseBranch), stoicDir))
  }

  val commitSha = getHeadSha(stoicDir)
  step("wait-build") {
    println("Waiting for GitHub Actions build workflow to complete...")
    val runId = waitForWorkflow("build", stoicDir, commitSha)

    println("Downloading build artifact...")
    check(runCommand(listOf("gh", "run", "download", runId, "--repo", "block/stoic",
      "-n", "stoic-release-tar-gz", "-D", artifactsDir.absolutePath), stoicDir))
  }

  val extractedDir = artifactsDir.resolve("verify")
  step("extract") {
    extractedDir.mkdirs()
    println("Extracting artifact to $extractedDir...")
    check(runCommand(listOf("tar", "-xzf",
      "${artifactsDir.resolve("stoic-release-tar-gz/stoic-release.tar.gz")}",
      "-C", extractedDir.absolutePath, "--strip-components", "1"), stoicDir))
  }

  val binPath = extractedDir.resolve("bin").absolutePath
  val newPath = "$binPath:${System.getenv("PATH")}"
  val env = mapOf("PATH" to newPath)

  step("verify-tests") {
    println("Running extended tests with verified artifact...")
    check(runCommand(listOf("test/test-demo-app-without-sdk.sh"), stoicDir, env))
    check(runCommand(listOf("test/test-plugin-new.sh"), stoicDir, env))
    check(runCommand(listOf("test/test-without-config.sh"), stoicDir, env))
    println("Local verification succeeded.")
  }

  val releaseTag = "v$releaseVersion"
  step("tag-release") {
    println("Tagging release as $releaseTag")
    check(runCommand(listOf("git", "tag", "-a", releaseTag, "-m", "Release $releaseVersion"), stoicDir))
    check(runCommand(listOf("git", "push", "origin", releaseTag), stoicDir))
  }

  step("wait-release") {
    println("Waiting for GitHub Actions release workflow to complete...")
    waitForWorkflow("release", stoicDir, commitSha)
  }

  step("merge-main") {
    println("Merging release branch into main...")
    check(runCommand(listOf("git", "checkout", "main"), stoicDir))
    check(runCommand(listOf("git", "merge", "--ff-only", releaseBranch), stoicDir))
  }

  step("bump-snapshot") {
    println("Bumping version to post-release: $postReleaseVersion")
    versionFile.writeText("$postReleaseVersion\n")
    check(runCommand(listOf("git", "add", versionFile.path), stoicDir))
    check(runCommand(listOf("git", "commit", "-m", "Start $postReleaseVersion development"), stoicDir))
    check(runCommand(listOf("git", "push", "origin", "main"), stoicDir))
    println("Release $releaseVersion completed successfully!")
  }

  println("Done")
}

/** Validate version string pattern. */
fun validateSemver(version: String): Boolean =
  Regex("""^\d+\.\d+\.\d+(-SNAPSHOT)?$""").matches(version)

/** Increment final semver component, always ending with -SNAPSHOT. */
fun incrementSemver(version: String): String {
  val clean = version.removeSuffix("-SNAPSHOT")
  val parts = clean.split(".").map { it.toInt() }
  return "${parts[0]}.${parts[1]}.${parts[2] + 1}-SNAPSHOT"
}

/** Ensure working tree clean. */
fun ensureCleanGitRepo(dir: File) {
  val status = runCommandOutput(listOf("git", "status", "--porcelain"), dir).trim()
  if (status.isNotEmpty()) {
    System.err.println(status)
    System.err.println("The git repository has uncommitted changes or untracked files. Aborting...")
    exitProcess(1)
  }
}

/** Run command, inheriting IO. */
fun runCommand(cmd: List<String>, dir: File, extraEnv: Map<String, String> = emptyMap()): Boolean {
  val pb = ProcessBuilder(cmd)
    .directory(dir)
    .inheritIO()
  pb.environment().putAll(extraEnv)
  val process = pb.start()
  return process.waitFor() == 0
}

/** Capture stdout from command. */
fun runCommandOutput(cmd: List<String>, dir: File): String {
  val process = ProcessBuilder(cmd)
    .directory(dir)
    .start()
  val output = process.inputStream.bufferedReader().readText()
  process.waitFor()
  return output
}


/**
 * Wait until a given workflow has completed successfully for the specified commit SHA.
 *
 * @param workflow  the workflow name (e.g., "build" or "release")
 * @param repoDir   local repo directory
 * @param commitSha the commit to wait for
 * @param timeoutMinutes how long to wait before failing
 */
fun waitForWorkflow(
  workflow: String,
  repoDir: File,
  commitSha: String,
  timeoutMinutes: Int = 40
): String {
  val repo = runCommandOutput(
    listOf("gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"),
    repoDir
  ).trim()

  val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
  var runId: String? = null

  // The jq filter, written as a raw triple-quoted string for readability.
  val jqFilter = """
    .[]
    | select(.headSha=="$commitSha") 
    | "\(.databaseId) \(.status) \(.conclusion)"
  """.trimIndent()

  while (true) {
    // Ask gh to give only runs for this commit, already filtered by jq
    val line = runCommandOutput(
      listOf(
        "gh", "run", "list",
        "--repo", repo,
        "--workflow", workflow,
        "--json", "databaseId,headSha,status,conclusion",
        "--jq", jqFilter,
        "--limit", "50"
      ),
      repoDir
    ).lines().firstOrNull { it.isNotBlank() }

    if (line != null) {
      val parts = line.trim().split(Regex("\\s+"))
      runId = parts[0]
      val status = parts.getOrNull(1)
      val conclusion = parts.getOrNull(2)

      when {
        status == "completed" && conclusion == "success" -> {
          println("Workflow '$workflow' succeeded for commit $commitSha.")
          return runId
        }
        status == "completed" && conclusion in listOf("failure", "cancelled", "timed_out") -> {
          System.err.println("❌ Workflow '$workflow' failed for commit $commitSha (conclusion=$conclusion).")
          println("See: https://github.com/$repo/actions/runs/$runId")
          exitProcess(1)
        }
      }
    }

    if (System.currentTimeMillis() > deadline) {
      System.err.println("❌ Timeout waiting for workflow '$workflow' to complete for commit $commitSha.")
      runId?.let { println("See: https://github.com/$repo/actions/runs/$it") }
      exitProcess(1)
    }

    println("⏳ Waiting for '$workflow' on commit $commitSha...")
    Thread.sleep(30_000)
  }
}

fun getHeadSha(repoDir: File): String =
  runCommandOutput(listOf("git", "rev-parse", "HEAD"), repoDir).trim()

// Order of steps for resume logic
private val stepOrder = listOf(
  "shellcheck",
  "create-branch",
  "wait-build",
  "extract",
  "verify-tests",
  "tag-release",
  "wait-release",
  "merge-main",
  "bump-snapshot"
)