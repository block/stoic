# For details on how to build for the ndk, see
# https://android.googlesource.com/platform/ndk/+/master/docs/BuildSystemMaintainers.md

include Makefile.inc

SRC := stoic.cc
OBJ := $(addprefix $(OUT_DIR)/, $(SRC:.cc=.o))
LIBS := $(OUT_DIR)/libnativehelper/libnativehelper.a $(OUT_DIR)/libbase/libbase.a
TARGET := $(OUT_DIR)/sync/stoic/stoic-jvmti-agent.so

.PHONY: all clean prepare

# Target to create the output directory
prepare:
	mkdir -p $(OUT_DIR)/

# Main target depends on prepare to ensure the output directory exists
all: prepare $(TARGET)

$(TARGET): $(OBJ) $(LIBS)
	$(CXX) $(LDFLAGS) -shared -o $@ $^ $(LIBS)

$(OUT_DIR)/%.o: %.cc
	$(CXX) $(CFLAGS) -c $< -o $@

$(OUT_DIR)/libnativehelper/libnativehelper.a:
	$(MAKE) -f Makefile.lnh all

$(OUT_DIR)/libbase/libbase.a:
	$(MAKE) -f Makefile.lb all

clean:
	rm -rf $(OUT_DIR)

debug:
	@echo "SRC = $(SRC)"
	@echo "OBJ = $(OBJ)"
	@echo "TARGET = $(TARGET)"
