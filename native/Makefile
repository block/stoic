# For details on how to build for the ndk, see
# https://android.googlesource.com/platform/ndk/+/master/docs/BuildSystemMaintainers.md

# Build for multiple architectures
ARCHS := arm64-v8a x86_64

# Map Android ABI names to NDK toolchain prefixes
ARCH_arm64-v8a_PREFIX := aarch64-linux-android
ARCH_x86_64_PREFIX := x86_64-linux-android

MIN_API := 26
NDK_PATH := $(ANDROID_NDK)
NDK_TOOLCHAIN := $(NDK_PATH)/toolchains/llvm/prebuilt/darwin-x86_64
SYSROOT := $(NDK_TOOLCHAIN)/sysroot

SRC := stoic.cc

.PHONY: all clean prepare $(ARCHS)

all: $(ARCHS)

# Build target for each architecture
$(ARCHS):
	@echo "Building for $@..."
	$(MAKE) -f Makefile.arch ARCH=$@ \
		ARCH_PREFIX=$(ARCH_$@_PREFIX) \
		MIN_API=$(MIN_API) \
		NDK_TOOLCHAIN=$(NDK_TOOLCHAIN) \
		SYSROOT=$(SYSROOT) \
		OUT_DIR=$(OUT_DIR)

clean:
	rm -rf $(OUT_DIR)

debug:
	@echo "ARCHS = $(ARCHS)"
	@echo "OUT_DIR = $(OUT_DIR)"
