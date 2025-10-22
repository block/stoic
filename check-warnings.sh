#!/bin/bash
# Script to check for compile warnings in the codebase
# Exits with non-zero status if warnings are found

set -e

echo "Building project and checking for warnings..."
echo "This includes all modules: Gradle, native C++, and GraalVM native-image"
echo ""

# Run clean build using build.sh to ensure all warnings are caught
# This matches what CI does and includes native code compilation
BUILD_OUTPUT=$(mktemp)
./gradlew clean && EXTRA_GRADLE_ARGS="--warning-mode all" AUTO_YES=1 ./build.sh 2>&1 | tee "$BUILD_OUTPUT"

echo ""
echo "Checking for warnings..."

# Extract warnings, excluding:
# - android.support.graphics.drawable: Third-party Android support library namespace conflict (can't fix)
# - Resolution of the configuration.*jar-to-apk-preserve-manifest:runtimeClasspath: Gradle deprecation with TODO in build.gradle.kts:214
# - exec(Action: Gradle exec() deprecation with TODO in build.gradle.kts:362
WARNINGS=$(grep -E "^w:|Warning:|deprecated" "$BUILD_OUTPUT" | \
  grep -v "android.support.graphics.drawable" | \
  grep -v "Resolution of the configuration.*jar-to-apk-preserve-manifest:runtimeClasspath" | \
  grep -v "exec(Action" || true)

# Clean up temp file
rm -f "$BUILD_OUTPUT"

# Check if any warnings were found
if [ -n "$WARNINGS" ]; then
  echo ""
  echo "❌ Found compile warnings:"
  echo "$WARNINGS"
  echo ""
  exit 1
else
  echo "✅ No compile warnings found!"
fi

# Note: Format checking with 'spotlessCheck' is configured but not yet enforced
# Once the codebase is formatted (via 'spotlessApply'), uncomment the following:
# echo ""
# echo "Checking code formatting..."
# ./gradlew spotlessCheck
# if [ $? -ne 0 ]; then
#   echo "❌ Code formatting violations found!"
#   echo "Run './gradlew spotlessApply' to fix them."
#   exit 1
# fi
# echo "✅ Code formatting is correct!"

exit 0
