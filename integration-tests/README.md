# Stoic Integration Tests

This module provides tools to run Stoic's end-to-end tests on specific Android emulator API levels.

## Overview

Stoic is a **host tool** that connects to **target devices**. These integration tests:
1. Create/start Android emulators with specific API levels
2. Run the stoic CLI from the host to connect to the emulator
3. Execute test scripts against running emulators
4. Verify functionality across different Android versions

## Quick Start

### Option 1: Run All Tests on Multiple API Levels (Fastest)

The `test/emulator-tests.sh` script runs all test suites on API 29 and API 34:

```bash
cd test
./emulator-tests.sh
```

This will automatically:
- Test on API 29 (Android 10) and API 34 (Android 14)
- Run all test suites on each API level
- Report a summary of results

### Option 2: Using the Standalone Script (Flexible)

The `test/with-emulator.sh` script manages the entire emulator lifecycle and runs your test:

```bash
# Run a single test suite on API 29
cd test
./with-emulator.sh 29 ./test-demo-app-without-sdk.sh

# Run all tests on API 34
./with-emulator.sh 34 ./run-all-tests-on-connected-device.sh
```

The script automatically:
- Creates the AVD if it doesn't exist
- Starts the emulator in headless mode
- Waits for the emulator to boot completely
- Runs your test script
- Cleans up and kills the emulator on exit

### Option 3: Using Gradle Tasks

For convenience, Gradle tasks wrap the standalone script:

```bash
# Run tests on specific API levels
./gradlew :integration-tests:testApi29
./gradlew :integration-tests:testApi34

# Run tests on all API levels (sequentially)
./gradlew :integration-tests:testAll

# List available test tasks
./gradlew :integration-tests:tasks --group=verification
```

## Supported API Levels

| API Level | Android Version |
|-----------|-----------------|
| 29        | Android 10      |
| 30        | Android 11      |
| 31        | Android 12      |
| 33        | Android 13      |
| 34        | Android 14      |

## How It Works

### The `with-emulator.sh` Script

```bash
./with-emulator.sh <api_level> <script_to_run> [script_args...]
```

**What it does:**
1. Validates the API level and checks for required tools
2. Creates an AVD using `avdmanager` (if it doesn't exist)
3. Starts the emulator in headless mode with optimized flags
4. Waits for the device to appear and boot completely
5. Runs your test script with environment variables set (`ANDROID_SERIAL`, `API_LEVEL`)
6. Cleans up by killing the emulator on exit (success or failure)

**Features:**
- Automatic AVD creation with sensible defaults
- Graceful cleanup via trap handlers
- Progress indicators during boot
- Exits with the same code as your test script

## Writing Your Own Tests

You can run any script with `with-emulator.sh`:

```bash
# Create a custom test script
cat > my-test.sh << 'EOF'
#!/usr/bin/env bash
set -euo pipefail

echo "Running on device: $ANDROID_SERIAL"
echo "API Level: $API_LEVEL"
adb shell getprop ro.build.version.sdk
# Your test commands here...
EOF

chmod +x my-test.sh

# Run it with an emulator
./with-emulator.sh 29 ./my-test.sh
```

The following environment variables are automatically set for your test script:
- `ANDROID_SERIAL` - The emulator device serial (e.g., `emulator-5554`)
- `API_LEVEL` - The Android API level (e.g., `29`, `34`)

## CI Integration

The standalone script is designed for CI/CD pipelines:

```yaml
# GitHub Actions example
- name: Run Stoic Tests on API 29
  run: |
    cd test
    ./with-emulator.sh 29 ./test-demo-app-without-sdk.sh

- name: Run Stoic Tests on API 34
  run: |
    cd test
    ./with-emulator.sh 34 ./test-demo-app-without-sdk.sh
```

Or using Gradle:

```yaml
- name: Run All Integration Tests
  run: ./gradlew :integration-tests:testAll
```

## Troubleshooting

### Missing system images

If the AVD creation fails, ensure you have the system image installed:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-29;default;x86_64"
```

### ANDROID_HOME not set

The script requires either `ANDROID_HOME` or `ANDROID_SDK_ROOT`:

```bash
export ANDROID_HOME=/path/to/android/sdk
```

### Emulator won't start

- Check disk space (emulators need ~4GB each)
- Verify hardware acceleration (Intel HAXM on Mac, KVM on Linux)
- Check that virtualization is enabled in BIOS

### Script hangs during boot

The script waits up to 2 minutes for the emulator to boot. If it consistently times out:
- Check system resources (CPU, RAM)
- Try running the emulator manually to see detailed output
- Check for conflicting emulators: `adb devices`

### Cleanup failed

If the emulator doesn't get killed properly:

```bash
# Manually kill all emulators
adb emu kill

# Or force kill
pkill -9 qemu-system
```

## Advanced Usage

### Running tests in parallel (not recommended)

While the script can technically run multiple emulators simultaneously, it's not recommended due to:
- High resource usage
- Potential port conflicts
- ADB device selection complexity

If you need parallel testing, use separate CI jobs:

```yaml
strategy:
  matrix:
    api: [29, 30, 31, 33, 34]
steps:
  - run: cd test && ./with-emulator.sh ${{ matrix.api }} ./test-demo-app-without-sdk.sh
```

### Adding new API levels

Edit both:
1. `test/with-emulator.sh` - Add to the `ANDROID_VERSIONS` array
2. `integration-tests/build.gradle.kts` - Add to the `apiLevels` list

## Architecture

```
test/
├── emulator-tests.sh                       # Run all tests on API 29, 30, 35 (main entry point)
├── with-emulator.sh                        # Standalone emulator manager
├── run-all-tests-on-connected-device.sh    # Run all test suites (used by with-emulator.sh)
├── test-demo-app-without-sdk.sh            # Individual test suite
├── test-plugin-new.sh                      # Individual test suite
├── test-without-config.sh                  # Individual test suite
└── ...

integration-tests/
├── build.gradle.kts              # Gradle wrapper tasks
└── README.md                     # This file
```

The design separates concerns:
- **Emulator management** lives in `test/with-emulator.sh` (no Gradle dependency)
- **Test orchestration** in `test/emulator-tests.sh` and `test/run-all-tests-on-connected-device.sh`
- **Individual test suites** are standalone scripts in `test/`
- **Gradle tasks** are thin wrappers for developer convenience

This allows you to use the emulator management script anywhere, with or without Gradle.
