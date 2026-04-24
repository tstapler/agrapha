# Builds libAudioCaptureBridge.dylib (Swift ScreenCaptureKit wrapper)
# and AudioCaptureBridgeJNI.dylib (Obj-C JNI bridge) from source.
#
# Prerequisites: Xcode command-line tools, JDK 17+
# Usage (from native/AudioCaptureBridge/):
#   make          -- build both libraries
#   make clean    -- remove build artifacts
#
# Output: both .dylib files are copied to composeApp/src/desktopMain/resources/
# so Gradle picks them up as classpath resources for System.load() extraction.

JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17+ 2>/dev/null || /usr/libexec/java_home)

# swift build -c release outputs to .build/release/ relative to Package.swift location
BUILD_DIR  = .build/release
SWIFT_LIB  = $(BUILD_DIR)/libAudioCaptureBridge.dylib

# JNI bridge headers live alongside AudioCaptureBridge.h
JNI_HEADER_DIR = Sources/AudioCaptureBridge

# Destination: Gradle will bundle these as resources, extracted at runtime via System.load()
OUTPUT_DIR = ../../composeApp/src/desktopMain/resources

CC = clang
# Build for the native architecture only (arm64 on Apple Silicon, x86_64 on Intel).
# For a universal binary, run `make universal` which uses lipo to combine slices.
ARCH ?= $(shell uname -m)

CFLAGS = -arch $(ARCH) \
         -mmacosx-version-min=13.0 \
         -I$(JAVA_HOME)/include \
         -I$(JAVA_HOME)/include/darwin \
         -I$(JNI_HEADER_DIR) \
         -ObjC

# Link against the Swift dylib; @loader_path lets the OS find it at the JNI dylib's location.
LDFLAGS = -dynamiclib \
          -arch $(ARCH) \
          -L$(BUILD_DIR) \
          -lAudioCaptureBridge \
          -framework Foundation \
          -Wl,-rpath,@loader_path \
          -install_name @rpath/AudioCaptureBridgeJNI.dylib

.PHONY: all framework jni clean

all: framework jni

framework:
	@echo "==> Building AudioCaptureBridge Swift library (may take ~30s)…"
	swift build -c release
	@echo "==> Built: $(SWIFT_LIB)"

jni: $(SWIFT_LIB) jni/AudioCaptureBridgeJNI.m jni/AudioCaptureBridgeJNI.h
	@echo "==> Building AudioCaptureBridgeJNI.dylib…"
	@mkdir -p $(OUTPUT_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) \
		jni/AudioCaptureBridgeJNI.m \
		-o $(OUTPUT_DIR)/AudioCaptureBridgeJNI.dylib
	@echo "==> Copying libAudioCaptureBridge.dylib to resources…"
	cp $(SWIFT_LIB) $(OUTPUT_DIR)/libAudioCaptureBridge.dylib
	@echo "==> Done. Libraries in $(OUTPUT_DIR):"
	@ls -lh $(OUTPUT_DIR)/*.dylib

clean:
	swift package clean
	rm -f $(OUTPUT_DIR)/AudioCaptureBridgeJNI.dylib
	rm -f $(OUTPUT_DIR)/libAudioCaptureBridge.dylib
