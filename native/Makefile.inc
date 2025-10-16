# Output directory (can be overridden via environment variable)
OUT_DIR ?= ../out

#
# MIN_API is the lowest Android API level anything we build can run on. Don't
# expect a sensible error message if you try to use it on a lower API level
# though. You'll just get an error about unresolved symbols or something like
# that.
#
# There is some documentation here:
# https://android.googlesource.com/platform/ndk/+/main/docs/BuildSystemMaintainers.md#Weak-symbols-for-API-definitions
#

# NDK setup
MIN_API := 26
NDK_PATH := $(ANDROID_NDK)
NDK_PLATFORM_LEVEL := android-$(MIN_API)
NDK_TOOLCHAIN := $(NDK_PATH)/toolchains/llvm/prebuilt/darwin-x86_64
SYSROOT := $(NDK_TOOLCHAIN)/sysroot

# Compiler setup
CC := $(NDK_TOOLCHAIN)/bin/aarch64-linux-android$(MIN_API)-clang
CXX := $(NDK_TOOLCHAIN)/bin/aarch64-linux-android$(MIN_API)-clang++
LD := $(CXX)

# Flags
CFLAGS := -fPIC -Wall -DANDROID -D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ -I$(SYSROOT)/usr/include -Ilibbase/include -Ilibnativehelper/header_only_include -g -O0
CXXFLAGS := $(CFLAGS)
LDFLAGS := -L$(SYSROOT)/usr/lib -static-libstdc++ -static-libgcc -std=c++2a -landroid -llog -lEGL -lGLESv2 -lm
