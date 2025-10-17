# Releasing Stoic

This document describes how to create a new release of Stoic.

## Prerequisites

Before starting a release, ensure you have:

1. **An ARM Mac**
   - The release script verifies that stoic works on Darwin arm64

2. **A physical Android device attached** (not an emulator)
   - The release script runs integration tests on the built artifacts
   - This ensures Stoic works correctly on macOS (not just in CI on Linux)
   - We could run CI on macOS, but that would complicate emulator usage and make tests slower
   - Check device is connected: `adb devices`

3. **Clean git working directory**
   - All changes must be committed
   - Must be on the `main` branch

4. **GitHub CLI authenticated**
   - Install: `brew install gh`
   - Login: `gh auth login`

## Release Process

The release script automates the entire release process. Run:

```bash
./release.sh
```

### What the Script Does

The script will:

1. **Validate and confirm** the release version
   - Shows current version (e.g., `0.6.1-SNAPSHOT`)
   - Shows release version (e.g., `0.6.1`)
   - Shows post-release version (e.g., `0.6.2-SNAPSHOT`)
   - Asks for confirmation before proceeding

2. **Run shellcheck** on all shell scripts

3. **Create release branch** (`release/X.Y.Z`)
   - Updates `prebuilt/STOIC_VERSION` to remove `-SNAPSHOT`
   - Commits and pushes the branch

4. **Wait for GitHub Actions build**
   - Monitors the `build` workflow for the release branch
   - Downloads the built artifacts when complete

5. **Extract and verify artifacts**
   - Extracts the release tarball
   - Adds the built binary to PATH

6. **Run integration tests** on your attached device
   - Verifies the correct stoic binary is being used (`which stoic`)
   - Verifies the version matches the release (`stoic --version`)
   - Runs all tests via `test/run-all-tests-on-connected-device.sh`
   - These tests verify the release works on macOS with real hardware

7. **Tag the release** (`vX.Y.Z`)
   - Creates an annotated git tag
   - Pushes the tag to GitHub

8. **Wait for GitHub Actions release workflow**
   - Publishes to Maven Central (plugin-sdk and app-sdk)
   - Creates GitHub release with tarball
   - Prints verification URLs:
     - GitHub Release: `https://github.com/block/stoic/releases/tag/vX.Y.Z`
     - Maven Central (plugin-sdk): `https://central.sonatype.com/artifact/com.squareup.stoic/plugin-sdk/X.Y.Z`
     - Maven Central (app-sdk): `https://central.sonatype.com/artifact/com.squareup.stoic/app-sdk/X.Y.Z`
   - Note: Maven Central artifacts may take up to 30 minutes to become available for download

9. **Merge release branch to main**
   - Fast-forward merge only

10. **Bump version for next development cycle**
    - Updates `prebuilt/STOIC_VERSION` to `X.Y.Z+1-SNAPSHOT`
    - Commits and pushes to main

11. **Update Homebrew formula**
    - Triggers `update-stoic.yaml` workflow in `block/homebrew-tap`
    - Waits for the workflow to complete
    - Prints verification URL: `https://github.com/block/homebrew-tap/blob/main/Formula/stoic.rb`

12. **Print success message** with all release artifact URLs

## Resuming Failed Releases

The release script supports automatic resumption if something fails partway through. The script tracks progress in `releases/X.Y.Z/.prepare_state`.

### How Resumption Works

Each major step in the release process is tracked:
- `SHELLCHECK` - Shellcheck validation
- `CREATE_BRANCH` - Release branch creation
- `WAIT_BUILD` - GitHub Actions build
- `EXTRACT` - Artifact extraction
- `VERIFY_TESTS` - Integration tests
- `TAG_RELEASE` - Git tag creation
- `WAIT_RELEASE` - GitHub Actions release
- `MERGE_MAIN` - Merge to main
- `BUMP_SNAPSHOT` - Version bump
- `UPDATE_HOMEBREW` - Homebrew formula update

If the script fails or is interrupted:
1. Fix the underlying issue (e.g., failing test, network error)
2. Run `./release.sh` again
3. The script will skip already-completed steps
4. It will resume from the first incomplete step

### To Restart from Scratch

If you need to completely restart a release:

```bash
# Delete the artifacts directory
rm -r releases/X.Y.Z

# Delete the release branch (if created)
git checkout main
git branch -D release/X.Y.Z
git push origin --delete release/X.Y.Z

# Delete the tag (if created)
git tag -d vX.Y.Z
git push origin --delete vX.Y.Z

# Run the release script again
./release.sh
```

## Version Numbering

Stoic uses semantic versioning (MAJOR.MINOR.PATCH):

- Development versions end with `-SNAPSHOT` (e.g., `0.6.1-SNAPSHOT`)
- Release versions have no suffix (e.g., `0.6.1`)
- The script automatically:
  - Strips `-SNAPSHOT` for the release
  - Increments the patch version and adds `-SNAPSHOT` for the next development cycle

## Troubleshooting

### Build workflow doesn't start
- GitHub Actions may be delayed. Wait a few minutes and the script will find it.

### Release workflow fails
- Check the workflow logs: `gh run view --repo block/stoic`
- Common issues:
  - Maven Central credentials not configured (repository secrets)
  - GPG signing key issues (repository secrets)

### Integration tests fail
- Make sure a device is attached: `adb devices`
- Check device is unlocked and screen is on
- Verify `ANDROID_HOME` is set correctly
- Check which stoic binary is being used: `which stoic` (should be from `releases/X.Y.Z/verify/bin/darwin-arm64/stoic`)
- Verify the version: `stoic --version` (should show the release version)
- Run tests manually: `test/run-all-tests-on-connected-device.sh`

### Homebrew update times out
- Check the workflow manually: `https://github.com/block/homebrew-tap/actions`
- The workflow may need manual intervention if there are conflicts
- You can manually trigger it: `gh workflow run update-stoic.yaml --repo block/homebrew-tap --field tag=vX.Y.Z`

## Release Artifacts

Each release produces:

1. **GitHub Release** (`https://github.com/block/stoic/releases/tag/vX.Y.Z`)
   - `stoic-release.tar.gz` - Complete release bundle with:
     - Native binaries (darwin-arm64)
     - JAR files
     - SDK artifacts
     - Demo plugins and apps
     - Prebuilt files

2. **Maven Central Artifacts**
   - `com.squareup.stoic:plugin-sdk:X.Y.Z` - Plugin development SDK (JAR)
   - `com.squareup.stoic:app-sdk:X.Y.Z` - App integration SDK (AAR)
   - Both include sources and javadoc

3. **Homebrew Formula**
   - Updated in `block/homebrew-tap` repository
   - Users can install with: `brew install block/tap/stoic`

## Post-Release

After a successful release:

1. Verify the GitHub release page looks correct
2. Test installing via Homebrew: `brew upgrade stoic` (if already installed) or `brew install block/tap/stoic`
3. Verify Maven Central artifacts are available (may take up to 30 minutes):
   - Check the URLs printed by the release script
