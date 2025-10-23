# Sourceable script to ensure tests use the correct stoic binary
#
# Usage: source test/setup-stoic-path.sh
#
# If STOIC_BIN is set, ensures that binary is used.
# If STOIC_BIN is not set, builds the JVM version and uses it.

# Determine the repository root directory
if [ -n "${ZSH_VERSION:-}" ]; then
    # This is zsh-specific, so disable shell check
    # shellcheck disable=SC2296
    _setup_stoic_script_path="${(%):-%N}"
elif [ -n "${BASH_VERSION:-}" ]; then
    _setup_stoic_script_path="${BASH_SOURCE[0]}"
else
    echo "Error: Unsupported shell (neither Bash nor Zsh)" >&2
    return 1
fi

_setup_stoic_script_dir="$(cd "$(dirname "$_setup_stoic_script_path")" && pwd)"
_setup_stoic_repo_dir="$(cd "$_setup_stoic_script_dir/.." && pwd)"

if [ -n "${STOIC_BIN:-}" ]; then
    # STOIC_BIN is set - use the specified binary
    if [ ! -f "$STOIC_BIN" ]; then
        echo "Error: STOIC_BIN is set to '$STOIC_BIN' but file does not exist" >&2
        return 1
    fi

    if [ ! -x "$STOIC_BIN" ]; then
        echo "Error: STOIC_BIN is set to '$STOIC_BIN' but file is not executable" >&2
        return 1
    fi

    # Create a temporary directory with a symlink to stoic
    # This ensures we don't pollute PATH with other binaries from STOIC_BIN's directory
    _setup_stoic_tmpdir="$(mktemp -d)"
    ln -s "$(cd "$(dirname "$STOIC_BIN")" && pwd)/$(basename "$STOIC_BIN")" "$_setup_stoic_tmpdir/stoic"

    export PATH="$_setup_stoic_tmpdir:$PATH"

    echo "Using stoic from STOIC_BIN: $STOIC_BIN" >&2
else
    # STOIC_BIN not set - build the JVM version
    echo "Building JVM version of stoic..." >&2

    # Change to repo directory, build, and return to original directory
    pushd "$_setup_stoic_repo_dir" > /dev/null

    # Build distribution but skip native compilation (same as bnr.sh)
    if ! ./gradlew --quiet --console=plain buildDistribution -x nativeCompile; then
        popd > /dev/null
        echo "Error: Failed to build stoic" >&2
        return 1
    fi

    popd > /dev/null

    # Set up PATH to use the JVM version
    _setup_stoic_jvm_dir="$_setup_stoic_repo_dir/build/distributions/bin/jvm"

    if [ ! -f "$_setup_stoic_jvm_dir/stoic" ]; then
        echo "Error: Build succeeded but $_setup_stoic_jvm_dir/stoic not found" >&2
        return 1
    fi

    # Create a temporary directory with a symlink to stoic
    # This ensures we don't pollute PATH with other binaries from the jvm bin directory
    _setup_stoic_tmpdir="$(mktemp -d)"
    ln -s "$_setup_stoic_jvm_dir/stoic" "$_setup_stoic_tmpdir/stoic"

    export PATH="$_setup_stoic_tmpdir:$PATH"

    echo "Using built JVM version: $_setup_stoic_jvm_dir/stoic" >&2
fi

# Verify stoic is now accessible
if ! command -v stoic &> /dev/null; then
    echo "Error: stoic command not found in PATH after setup" >&2
    return 1
fi

# Show which stoic will be used
# echo "stoic command resolves to: $(command -v stoic)" >&2

# Clean up temporary variables (but not _setup_stoic_tmpdir - it needs to persist)
unset _setup_stoic_script_path
unset _setup_stoic_script_dir
unset _setup_stoic_repo_dir
unset _setup_stoic_jvm_dir
