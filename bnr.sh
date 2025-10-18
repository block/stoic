#!/bin/bash
# Build and run stoic (JVM version only, skipping slow GraalVM native compilation)

set -e

cd "$(dirname "$0")"

# Build distribution but skip native compilation
# Use --quiet to suppress output except errors
./gradlew --quiet --console=plain buildDistribution -x nativeCompile

# Run the JVM version using the wrapper script
exec build/distributions/bin/jvm/stoic "$@"
