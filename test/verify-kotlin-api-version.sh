#!/bin/bash
set -euo pipefail

# This script verifies that published SDK artifacts are compiled with Kotlin API version 1.9
# to ensure maximum compatibility with consumers.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure we're using the correct stoic binary
source "$SCRIPT_DIR/setup-stoic-path.sh"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "Verifying Kotlin API version in published SDK artifacts..."
echo

# Expected Kotlin API version
EXPECTED_API_VERSION="1.9"

verify_jar_kotlin_version() {
  local jar_path="$1"
  local artifact_name="$2"

  if [[ ! -f "$jar_path" ]]; then
    echo -e "${RED}✗ $artifact_name not found at: $jar_path${NC}"
    return 1
  fi

  echo "Checking $artifact_name..."

  # Extract .kotlin_module files from the JAR
  local temp_dir=$(mktemp -d)
  trap "rm -rf $temp_dir" EXIT

  unzip -q "$jar_path" "META-INF/*.kotlin_module" -d "$temp_dir" 2>/dev/null || {
    echo -e "${RED}✗ No .kotlin_module files found in $artifact_name${NC}"
    return 1
  }

  # Read the Kotlin metadata version from the first few bytes
  # Kotlin metadata format: first 4 bytes are magic number, next bytes contain version info
  local kotlin_module_file=$(find "$temp_dir/META-INF" -name "*.kotlin_module" | head -n 1)

  if [[ ! -f "$kotlin_module_file" ]]; then
    echo -e "${RED}✗ Could not find kotlin_module file in $artifact_name${NC}"
    return 1
  fi

  # Use kotlinp (Kotlin protobuf tool) if available, otherwise use kotlinc -version as fallback
  # For now, we'll check if the JAR was compiled with the expected API version by
  # examining the Kotlin stdlib dependency version in the manifest

  # Extract MANIFEST.MF
  unzip -q "$jar_path" "META-INF/MANIFEST.MF" -d "$temp_dir" 2>/dev/null || true

  # Check if we can find version info in manifest or module file
  local manifest="$temp_dir/META-INF/MANIFEST.MF"
  if [[ -f "$manifest" ]]; then
    if grep -q "Kotlin" "$manifest"; then
      echo "  Found Kotlin metadata in manifest"
    fi
  fi

  # Check the actual bytecode version - Kotlin 1.9 should produce specific bytecode
  # We'll use javap to check the class file version
  # Extract a sample .class file
  local class_file=$(unzip -l "$jar_path" "*.class" 2>/dev/null | grep -o '[^ ]*\.class' | head -n 1)

  if [[ -n "$class_file" ]]; then
    unzip -q "$jar_path" "$class_file" -d "$temp_dir" 2>/dev/null || true
    local extracted_class="$temp_dir/$class_file"

    if [[ -f "$extracted_class" ]]; then
      # Check the Kotlin metadata annotation
      javap -v "$extracted_class" 2>/dev/null | grep -A 5 "kotlin.Metadata" > "$temp_dir/metadata.txt" || true

      if [[ -f "$temp_dir/metadata.txt" ]] && [[ -s "$temp_dir/metadata.txt" ]]; then
        echo "  Found Kotlin metadata annotation"

        # The metadata contains version information
        # For Kotlin 1.9, we expect specific version numbers in the metadata
        # mv (metadata version) should be [1, 9, 0] for Kotlin 1.9
        if grep -q "mv.*1.*9" "$temp_dir/metadata.txt" 2>/dev/null; then
          echo -e "${GREEN}✓ $artifact_name compiled with Kotlin API version 1.9${NC}"
          return 0
        else
          echo -e "${YELLOW}  Metadata version info:${NC}"
          grep "mv" "$temp_dir/metadata.txt" || echo "  (no mv field found)"
          echo -e "${RED}✗ $artifact_name may not be using Kotlin API version 1.9${NC}"
          return 1
        fi
      fi
    fi
  fi

  echo -e "${YELLOW}⚠ Could not definitively verify Kotlin version for $artifact_name${NC}"
  echo "  Manual verification recommended"
  return 1
}

verify_aar_kotlin_version() {
  local aar_path="$1"
  local artifact_name="$2"

  if [[ ! -f "$aar_path" ]]; then
    echo -e "${RED}✗ $artifact_name not found at: $aar_path${NC}"
    return 1
  fi

  echo "Checking $artifact_name..."

  # Extract classes.jar from the AAR
  local temp_dir=$(mktemp -d)
  trap "rm -rf $temp_dir" EXIT

  unzip -q "$aar_path" "classes.jar" -d "$temp_dir" 2>/dev/null || {
    echo -e "${RED}✗ No classes.jar found in $artifact_name${NC}"
    return 1
  }

  # Now verify the JAR inside the AAR
  verify_jar_kotlin_version "$temp_dir/classes.jar" "$artifact_name (classes.jar)"
}

# Find the version from STOIC_VERSION
VERSION=$(cat "$PROJECT_ROOT/prebuilt/STOIC_VERSION" | tr -d '\n')
echo "Project version: $VERSION"
echo

# Verify plugin-sdk JAR
SDK_DIR="$(dirname "$(realpath "$(which stoic)")")/../../sdk"
PLUGIN_SDK_JAR="$SDK_DIR/stoic-plugin-sdk.jar"
verify_jar_kotlin_version "$PLUGIN_SDK_JAR" "plugin-sdk"

echo

# Verify app-sdk AAR
APP_SDK_AAR="$SDK_DIR/stoic-app-sdk.aar"
verify_aar_kotlin_version "$APP_SDK_AAR" "app-sdk"

echo
echo -e "${GREEN}All Kotlin API version checks completed${NC}"
