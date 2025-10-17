package com.squareup.stoic.release

import com.squareup.stoic.bridge.versionCodeFromVersionName
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    System.err.println("Usage: prepareRelease <path-to-stoic-root>")
    exitProcess(1)
  }

  val stoicDir = File(File(args[0]).absolutePath)

  val versionFile = stoicDir.resolve("prebuilt/STOIC_VERSION")
  val currentVersion = versionFile.readText().trim()
  validateSemver(currentVersion)

  val releaseVersion = currentVersion.removeSuffix("-SNAPSHOT")
  validateSemver(releaseVersion)

  val postReleaseVersion = incrementSemver(releaseVersion)
  validateSemver(postReleaseVersion)

  val releaseTag = "v$releaseVersion"

  // Release artifact URLs
  val githubReleaseUrl = "https://github.com/block/stoic/releases/tag/$releaseTag"
  val pluginSdkUrl = "https://central.sonatype.com/artifact/com.squareup.stoic/plugin-sdk/$releaseVersion"
  val appSdkUrl = "https://central.sonatype.com/artifact/com.squareup.stoic/app-sdk/$releaseVersion"
  val homebrewFormulaUrl = "https://github.com/block/homebrew-tap/blob/main/Formula/stoic.rb"

  println()
  println("═══════════════════════════════════════════════════════════════")
  println("  Stoic Release Process")
  println("═══════════════════════════════════════════════════════════════")
  println()
  println("  Current version:     $currentVersion")
  println("  Release version:     $releaseVersion")
  println("  Post-release version: $postReleaseVersion")
  println()
  println("  This will:")
  println("    • Create release branch: release/$releaseVersion")
  println("    • Tag the release as: $releaseTag")
  println("    • Publish to Maven Central")
  println("    • Create GitHub release")
  println("    • Update Homebrew formula")
  println("    • Merge to main and bump version")
  println()
  print("Do you want to proceed with this release? (yes/no): ")
  System.out.flush()

  val response = readLine()?.trim()?.lowercase()
  if (response != "yes") {
    println("Release cancelled.")
    exitProcess(0)
  }

  println()
  println("Starting release process...")
  println()

  println("""
    If you want to abandon the release, you can delete the artifacts directory with:
    % rm -r releases/$releaseVersion

    If the release branch has already been created, you can delete it with:
    % git checkout main && git branch -D release/$releaseVersion && git push origin --delete release/$releaseVersion

    If the tag has already been created, you can delete it with:
    % git tag -d v$releaseVersion && git push origin --delete v$releaseVersion


  """.trimIndent())

  ensureCleanGitRepo(stoicDir)
  val artifactsDir = stoicDir.resolve("releases/$releaseVersion")

  if (artifactsDir.exists()) {
    println("Resuming from previous attempt: $artifactsDir")
  } else {
    println("Creating artifacts directory: $artifactsDir")
    artifactsDir.mkdirs()
  }

  // state tracking
  val stateFile = artifactsDir.resolve(".prepare_state")
  fun markStep(step: Step) {
    println("Completed: $step")
    stateFile.writeText(step.name)
  }
  fun lastStep(): Step? = if (stateFile.exists()) Step.valueOf(stateFile.readText().trim()) else null
  fun shouldRun(step: Step): Boolean {
    val done = lastStep()
    return done == null || step.ordinal > done.ordinal
  }
  fun step(step: Step, action: () -> Unit) {
    if (shouldRun(step)) {
      println("Running step: ${step.name}")
      action()
      markStep(step)
    } else {
      println("Skipping already completed step: ${step.name}")
    }
  }

  lastStep()?.let { println("Resuming from step: $it") }

  step(Step.SHELLCHECK) {
    check(runCommand(listOf("test/shellcheck.sh"), stoicDir))
  }

  val releaseBranch = "release/$releaseVersion"
  step(Step.CREATE_BRANCH) {
    val branch = runCommandOutput(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), stoicDir).trim()
    val normalized = branch.removePrefix("refs/").removePrefix("heads/").removePrefix("origin/")
    require(normalized == "main") { "Must run from main branch (currently on '$normalized')" }

    require(currentVersion.endsWith("-SNAPSHOT")) {
      "Current version must end in -SNAPSHOT (was '$currentVersion')"
    }
    require(versionCodeFromVersionName(releaseVersion) == versionCodeFromVersionName(currentVersion) + 1)
    require(versionCodeFromVersionName(postReleaseVersion) == versionCodeFromVersionName(releaseVersion) + 9)

    println("Creating release branch: $releaseBranch")
    check(runCommand(listOf("git", "checkout", "-b", releaseBranch), stoicDir))
    versionFile.writeText("$releaseVersion\n")
    check(runCommand(listOf("git", "add", versionFile.path), stoicDir))
    check(runCommand(listOf("git", "commit", "-m", "Prepare $releaseVersion release"), stoicDir))
    check(runCommand(listOf("git", "push", "--set-upstream", "origin", releaseBranch), stoicDir))
  }

  val commitSha = getHeadSha(stoicDir)
  step(Step.WAIT_BUILD) {
    println("Waiting for GitHub Actions build workflow to complete...")
    val runId = waitForWorkflow("build", stoicDir, commitSha)

    println("Downloading build artifact...")
    check(runCommand(listOf("gh", "run", "download", runId, "--repo", "block/stoic",
      "-n", "stoic-release-tar-gz", "-D", artifactsDir.absolutePath), stoicDir))
  }

  val extractedDir = artifactsDir.resolve("verify")
  step(Step.EXTRACT) {
    extractedDir.mkdirs()
    println("Extracting artifact to $extractedDir...")
    check(runCommand(listOf("tar", "-xzf",
      "${artifactsDir.resolve("stoic-release.tar.gz")}",
      "-C", extractedDir.absolutePath, "--strip-components", "1"), stoicDir))
  }

  val binPath = extractedDir.resolve("bin/darwin-arm64").absolutePath
  val newPath = "$binPath:${System.getenv("PATH")}"
  val env = mapOf("PATH" to newPath)

  step(Step.VERIFY_TESTS) {
    println("Verifying stoic binary location...")
    val whichOutput = runCommandOutput(listOf("which", "stoic"), stoicDir, env).trim()
    val expectedPath = extractedDir.resolve("bin/darwin-arm64/stoic").absolutePath
    println("which stoic: $whichOutput")
    if (whichOutput != expectedPath) {
      System.err.println("❌ Wrong stoic binary! Expected $expectedPath but got: $whichOutput")
      exitProcess(1)
    }
    println("✓ Using correct stoic binary from release artifacts")
    println()

    println("Verifying stoic version...")
    val versionOutput = runCommandOutput(listOf("stoic", "--version"), stoicDir, env).trim()
    println("stoic --version: $versionOutput")
    if (!versionOutput.contains(releaseVersion)) {
      System.err.println("❌ Version mismatch! Expected $releaseVersion but got: $versionOutput")
      exitProcess(1)
    }
    println("✓ Version verified: $releaseVersion")
    println()

    println("Running all tests on connected device...")
    check(runCommand(listOf("test/run-all-tests-on-connected-device.sh"), stoicDir, env))
    println("✓ All tests passed on macOS with real hardware.")
  }

  step(Step.TAG_RELEASE) {
    println("Tagging release as $releaseTag")
    check(runCommand(listOf("git", "tag", "-a", releaseTag, "-m", "Release $releaseVersion"), stoicDir))
    check(runCommand(listOf("git", "push", "origin", releaseTag), stoicDir))
  }

  step(Step.WAIT_RELEASE) {
    println("Waiting for GitHub Actions release workflow to complete...")
    waitForWorkflow("release", stoicDir, commitSha)
    println()
    println("GitHub release and Maven Central artifacts published:")
    println("  GitHub Release: $githubReleaseUrl")
    println("  Maven Central (plugin-sdk): $pluginSdkUrl")
    println("  Maven Central (app-sdk): $appSdkUrl")
    println()
    println("Note: Maven Central artifacts may take up to 30 minutes to become available for download.")
  }

  step(Step.MERGE_MAIN) {
    println("Merging release branch into main...")
    check(runCommand(listOf("git", "checkout", "main"), stoicDir))
    check(runCommand(listOf("git", "merge", "--ff-only", releaseBranch), stoicDir))
  }

  step(Step.BUMP_SNAPSHOT) {
    println("Bumping version to post-release: $postReleaseVersion")
    versionFile.writeText("$postReleaseVersion\n")
    check(runCommand(listOf("git", "add", versionFile.path), stoicDir))
    check(runCommand(listOf("git", "commit", "-m", "Start $postReleaseVersion development"), stoicDir))
    check(runCommand(listOf("git", "push", "origin", "main"), stoicDir))
  }

  step(Step.UPDATE_HOMEBREW) {
    println("Triggering Homebrew tap update for $releaseTag...")
    check(runCommand(listOf("gh", "workflow", "run", "update-stoic.yaml",
      "--repo", "block/homebrew-tap",
      "--field", "tag=$releaseTag"), stoicDir))

    println("Waiting for Homebrew tap update workflow to complete...")
    // Get the latest commit SHA from homebrew-tap main branch to wait for the workflow
    val homebrewRepoDir = stoicDir // We're triggering from stoic repo
    waitForWorkflowInRepo("update-stoic.yaml", "block/homebrew-tap", stoicDir, timeoutMinutes = 10)

    println()
    println("Homebrew formula updated:")
    println("  Homebrew formula: $homebrewFormulaUrl")
    println()

    // Success message is intentionally inside the last step so we can distinguish
    // between a fresh successful release and resuming an already-completed release
    println("Release $releaseVersion completed successfully!")
    println()
    println("All release artifacts:")
    println("  GitHub Release: $githubReleaseUrl")
    println("  Maven Central (plugin-sdk): $pluginSdkUrl")
    println("  Maven Central (app-sdk): $appSdkUrl")
    println("  Homebrew formula: $homebrewFormulaUrl")
    println()
  }
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
fun runCommandOutput(cmd: List<String>, dir: File, extraEnv: Map<String, String> = emptyMap()): String {
  val pb = ProcessBuilder(cmd)
    .directory(dir)
  pb.environment().putAll(extraEnv)
  val process = pb.start()
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

    var runUrl: String? = null
    if (line != null) {
      val parts = line.trim().split(Regex("\\s+"))
      runId = parts[0]
      val status = parts.getOrNull(1)
      val conclusion = parts.getOrNull(2)
      runUrl = "https://github.com/$repo/actions/runs/$runId"

      when {
        status == "completed" && conclusion == "success" -> {
          println("Workflow '$workflow' succeeded for commit $commitSha.")
          return runId
        }
        status == "completed" && conclusion in listOf("failure", "cancelled", "timed_out") -> {
          System.err.println("❌ Workflow '$workflow' failed for commit $commitSha (conclusion=$conclusion).")
          println("See: $runUrl")
          exitProcess(1)
        }
      }
    }

    if (System.currentTimeMillis() > deadline) {
      System.err.println("❌ Timeout waiting for workflow '$workflow' to complete for commit $commitSha.")
      runUrl?.let { println("See: $it") }
      exitProcess(1)
    }

    println("Waiting for '$workflow' on commit $commitSha...")
    runUrl?.let { println("See: $it") }
    Thread.sleep(30_000)
  }
}

fun getHeadSha(repoDir: File): String =
  runCommandOutput(listOf("git", "rev-parse", "HEAD"), repoDir).trim()

/**
 * Wait for the most recent workflow run to complete in a different repository.
 * Used when we trigger a workflow but don't know the commit SHA in that repo.
 *
 * @param workflow  the workflow name (e.g., "update-stoic.yaml")
 * @param repo      the repository in owner/name format (e.g., "block/homebrew-tap")
 * @param localDir  local directory to run commands from
 * @param timeoutMinutes how long to wait before failing
 */
fun waitForWorkflowInRepo(
  workflow: String,
  repo: String,
  localDir: File,
  timeoutMinutes: Int = 10
) {
  val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
  var runId: String? = null
  var lastStatus: String? = null

  // Give the workflow a moment to start
  Thread.sleep(5_000)

  while (true) {
    // Get the most recent workflow run
    val line = runCommandOutput(
      listOf(
        "gh", "run", "list",
        "--repo", repo,
        "--workflow", workflow,
        "--json", "databaseId,status,conclusion",
        "--jq", """.[0] | "\(.databaseId) \(.status) \(.conclusion)"""",
        "--limit", "1"
      ),
      localDir
    ).trim()

    if (line.isNotBlank()) {
      val parts = line.split(Regex("\\s+"))
      val currentRunId = parts[0]
      val status = parts.getOrNull(1)
      val conclusion = parts.getOrNull(2)
      val runUrl = "https://github.com/$repo/actions/runs/$currentRunId"

      // Track the run ID on first encounter
      if (runId == null) {
        runId = currentRunId
        println("Tracking workflow run: $runUrl")
      }

      // Only process if this is still the same run we're tracking
      if (currentRunId == runId) {
        when {
          status == "completed" && conclusion == "success" -> {
            println("Workflow '$workflow' succeeded in $repo.")
            return
          }
          status == "completed" && conclusion in listOf("failure", "cancelled", "timed_out") -> {
            System.err.println("❌ Workflow '$workflow' failed in $repo (conclusion=$conclusion).")
            println("See: $runUrl")
            exitProcess(1)
          }
          status != lastStatus -> {
            println("Workflow status: $status")
            lastStatus = status
          }
        }
      }
    }

    if (System.currentTimeMillis() > deadline) {
      System.err.println("❌ Timeout waiting for workflow '$workflow' in $repo to complete.")
      runId?.let { println("See: https://github.com/$repo/actions/runs/$it") }
      exitProcess(1)
    }

    Thread.sleep(10_000)
  }
}

// Order of steps for resume logic
private enum class Step {
  SHELLCHECK,
  CREATE_BRANCH,
  WAIT_BUILD,
  EXTRACT,
  VERIFY_TESTS,
  TAG_RELEASE,
  WAIT_RELEASE,
  MERGE_MAIN,
  BUMP_SNAPSHOT,
  UPDATE_HOMEBREW
}
