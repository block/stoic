#!/usr/bin/env bash
set -euo pipefail

# Script to spin up an Android emulator and run a test script
# Usage: ./with-emulator.sh <api_level> <script_to_run> [script_args...]
#
# Example:
#   ./with-emulator.sh 29 ./test-demo-app-without-sdk.sh
#   ./with-emulator.sh 34 ./test-demo-app-without-sdk.sh --verbose

if [ $# -lt 2 ]; then
    echo "Usage: $0 <api_level> <script_to_run> [script_args...]" >&2
    echo "" >&2
    echo "Examples:" >&2
    echo "  $0 29 ./test-demo-app-without-sdk.sh" >&2
    echo "  $0 34 ./test-demo-app-without-sdk.sh --verbose" >&2
    echo "" >&2
    echo "Supported API levels: 26, 29, 30, 31, 32, 33, 34, 35" >&2
    exit 1
fi

API_LEVEL="$1"
TEST_SCRIPT="$2"
shift 2
TEST_ARGS=("$@")

# Configuration mapping
declare -A ANDROID_VERSIONS=(
    [26]="8.0"
    [29]="10.0"
    [30]="11.0"
    [31]="12.0"
    [32]="12.0L"
    [33]="13.0"
    [34]="14.0"
    [35]="15.0"
)

# System image type mapping (use default for all - no need for google_apis)
declare -A SYSTEM_IMAGE_TYPES=(
    [26]="default"
    [29]="default"
    [30]="default"
    [31]="default"
    [32]="default"
    [33]="default"
    [34]="default"
    [35]="default"
)

if [ -z "${ANDROID_VERSIONS[$API_LEVEL]:-}" ]; then
    echo "Error: Unsupported API level: $API_LEVEL" >&2
    echo "Supported API levels: ${!ANDROID_VERSIONS[@]}" >&2
    exit 1
fi

ANDROID_VERSION="${ANDROID_VERSIONS[$API_LEVEL]}"
AVD_NAME="stoic_test_api${API_LEVEL}"
ABI="arm64-v8a"
DEVICE="pixel_2"
SYSTEM_IMAGE_TYPE="${SYSTEM_IMAGE_TYPES[$API_LEVEL]}"
SYSTEM_IMAGE="system-images;android-${API_LEVEL};${SYSTEM_IMAGE_TYPE};${ABI}"

# Get Android SDK path
if [ -n "${ANDROID_HOME:-}" ]; then
    SDK_PATH="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    SDK_PATH="$ANDROID_SDK_ROOT"
else
    echo "Error: ANDROID_HOME or ANDROID_SDK_ROOT environment variable not set" >&2
    exit 1
fi

AVDMANAGER="$SDK_PATH/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$SDK_PATH/cmdline-tools/latest/bin/sdkmanager"
EMULATOR="$SDK_PATH/emulator/emulator"
ADB="$SDK_PATH/platform-tools/adb"

# Verify tools exist
for tool in "$AVDMANAGER" "$SDKMANAGER" "$EMULATOR" "$ADB"; do
    if [ ! -x "$tool" ]; then
        echo "Error: Required tool not found or not executable: $tool" >&2
        exit 1
    fi
done

# Verify test script exists and is executable
if [ ! -f "$TEST_SCRIPT" ]; then
    echo "Error: Test script not found: $TEST_SCRIPT" >&2
    exit 1
fi

if [ ! -x "$TEST_SCRIPT" ]; then
    echo "Error: Test script is not executable: $TEST_SCRIPT" >&2
    exit 1
fi

echo "================================================"
echo "Android Emulator Test Runner"
echo "================================================"
echo "API Level:        $API_LEVEL (Android $ANDROID_VERSION)"
echo "AVD Name:         $AVD_NAME"
echo "Test Script:      $TEST_SCRIPT"
echo "Test Args:        ${TEST_ARGS[*]:-<none>}"
echo "================================================"
echo ""

# Function to cleanup on exit
cleanup() {
    local exit_code=$?
    echo ""
    echo "Cleaning up..."

    # Kill logcat
    if [ -n "${LOGCAT_PID:-}" ] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
        echo "Stopping logcat (PID: $LOGCAT_PID)..."
        kill "$LOGCAT_PID" 2>/dev/null || true
    fi

    # Kill emulator
    if [ -n "${EMULATOR_PID:-}" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "Stopping emulator (PID: $EMULATOR_PID)..."
        "$ADB" emu kill 2>/dev/null || true
        # Wait a bit for graceful shutdown
        sleep 2
        # Force kill if still running
        if kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill -9 "$EMULATOR_PID" 2>/dev/null || true
        fi
    fi

    echo "Cleanup complete."
    exit $exit_code
}

trap cleanup EXIT INT TERM

# Check if system image is installed, install if not
echo "Checking if system image is installed..."
if ! "$SDKMANAGER" --list_installed | grep -q "^  $SYSTEM_IMAGE"; then
    echo "System image not found. Installing: $SYSTEM_IMAGE"
    echo "This may take a few minutes..."

    # yes fails with SIGPIPE, even when the command succeeds
    yes | "$SDKMANAGER" "$SYSTEM_IMAGE" || true
    if ! "$SDKMANAGER" --list_installed | grep -q "^  $SYSTEM_IMAGE"; then
      echo "System image install failed."
      exit 1
    else
      echo "System image installed successfully."
    fi
else
    echo "System image already installed: $SYSTEM_IMAGE"
fi

# Create AVD if it doesn't exist
echo "Checking if AVD exists..."
if "$AVDMANAGER" list avd -c | grep -q "^${AVD_NAME}$"; then
    echo "AVD '$AVD_NAME' already exists."
else
    echo "Creating AVD '$AVD_NAME'..."
    echo "no" | "$AVDMANAGER" create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device "$DEVICE" \
        --force
    echo "AVD created successfully."
fi

echo ""

# Record existing emulators before starting ours
PRE_EXISTING_DEVICES=$("$ADB" devices | awk '{print $1}' | sort || true)

echo "Starting emulator..."

# Redirect emulator output to log file
EMULATOR_LOG_DIR="/tmp/.stoic"
EMULATOR_LOG_FILE="$EMULATOR_LOG_DIR/emulator-startup.log"
mkdir -p "$EMULATOR_LOG_DIR"

"$EMULATOR" \
    -avd "$AVD_NAME" \
    -no-snapshot-save \
    -no-audio \
    -no-boot-anim \
    -no-window \
    -gpu swiftshader_indirect \
    > "$EMULATOR_LOG_FILE" 2>&1 &

EMULATOR_PID=$!
echo "Emulator started (PID: $EMULATOR_PID)"
echo "Emulator output: $EMULATOR_LOG_FILE"

echo ""
echo "Waiting for emulator to appear in adb devices..."
WAIT_COUNT=0
MAX_WAIT=90  # Increased from 60 to 90 (3 minutes)
NEW_EMULATOR=""
while [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    # Get current emulators
    CURRENT_EMULATORS=$("$ADB" devices | grep "emulator-" | awk '{print $1}' | sort || true)

    # Find the new emulator (present in CURRENT but not in EXISTING)
    # comm -13: suppress lines unique to first file and common lines, showing
    #   only lines unique to second file
    NEW_EMULATOR=$(comm -13 <(echo "$PRE_EXISTING_DEVICES") <(echo "$CURRENT_EMULATORS") | head -1)

    if [ -n "$NEW_EMULATOR" ]; then
        # Check if it's fully booted (shows as "device" not just "offline")
        if "$ADB" -s "$NEW_EMULATOR" get-state 2>/dev/null | grep -q "device"; then
            break
        fi
    fi

    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 1))
    echo -n "."
done
echo ""

if [ -z "$NEW_EMULATOR" ]; then
    echo "Error: New emulator did not appear in adb devices within 3 minutes" >&2
    echo "" >&2
    echo "Emulator startup log ($EMULATOR_LOG_FILE):" >&2
    echo "===========================================" >&2
    cat "$EMULATOR_LOG_FILE" >&2
    echo "===========================================" >&2
    exit 1
fi

# Use the newly started emulator
export ANDROID_SERIAL="$NEW_EMULATOR"
echo "Emulator device: $ANDROID_SERIAL"

echo ""
echo "Waiting for emulator to finish booting..."
"$ADB" wait-for-device
"$ADB" shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'

echo "Emulator is ready!"
echo ""

# Start capturing logcat
LOGCAT_DIR="/tmp/.stoic"
LOGCAT_FILE="$LOGCAT_DIR/with-emulator-logcat.txt"
mkdir -p "$LOGCAT_DIR"

echo "Starting logcat capture to $LOGCAT_FILE ..."
# Clear logcat and start capturing from current time with timestamp
#"$ADB" logcat -c
"$ADB" logcat -v time > "$LOGCAT_FILE" &
LOGCAT_PID=$!
echo "Logcat started (PID: $LOGCAT_PID)"
echo ""

echo "================================================"
echo "Running test script..."
echo "================================================"
echo ""

# Set environment variables for the test script
export API_LEVEL="$API_LEVEL"

# Uncomment for better native stack traces
# adb shell su 0 setenforce 0

# Run the test script
set +e
"$TEST_SCRIPT" "${TEST_ARGS[@]}"
TEST_EXIT_CODE=$?
set -e

echo ""
echo "================================================"
echo "Test script completed with exit code: $TEST_EXIT_CODE"
echo "================================================"

# Cleanup will happen via trap
exit $TEST_EXIT_CODE
