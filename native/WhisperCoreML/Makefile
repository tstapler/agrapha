# Builds libwhisperjni-coreml.dylib — the CoreML-accelerated JNI dylib for WhisperService.
#
# This replaces the CPU-only dylib bundled in the whisper-jni Maven artifact with a build
# that enables Apple Neural Engine acceleration via CoreML (+DWHISPER_COREML=1).
# Whisper's CoreML path uses a pre-compiled encoder bundle ({model}-encoder.mlmodelc)
# downloaded alongside the GGML model file; the decoder still runs on CPU.
#
# Prerequisites:
#   - Xcode 15+ (CoreML compiler: coremlc)
#   - JDK 17+ (for JNI headers)
#   - CMake 3.21+
#   - curl / git
#
# Usage (from native/WhisperCoreML/):
#   make                     -- clone, build, install
#   make ARCH=x86_64         -- force Intel slice
#   make universal           -- build arm64 + x86_64 and lipo into fat dylib
#   make clean               -- remove build dir
#
# Output: libwhisperjni-coreml.dylib is installed to composeApp/src/desktopMain/resources/
# WhisperService.loadLibraryOnce() extracts it at runtime from the classpath and loads it
# via System.load(), falling back to the CPU dylib from the whisper-jni Maven artifact.

JAVA_HOME      ?= $(shell /usr/libexec/java_home -v 17+ 2>/dev/null || /usr/libexec/java_home)
ARCH           ?= $(shell uname -m)
WHISPER_JNI_TAG ?= v1.6.1   # must match libs.versions.toml whisper-jni version

BUILD_DIR      = build
WHISPER_JNI_DIR = $(BUILD_DIR)/whisper-jni
OUTPUT_DIR     = ../../composeApp/src/desktopMain/resources
OUTPUT_DYLIB   = $(OUTPUT_DIR)/libwhisperjni-coreml.dylib

CMAKE_BUILD    = $(WHISPER_JNI_DIR)/build-coreml-$(ARCH)

.PHONY: all clone configure build install universal clean

all: clone configure build install

# ── Step 1: Clone whisper-jni at the version matching the Maven dependency ────

clone: $(WHISPER_JNI_DIR)/.git

$(WHISPER_JNI_DIR)/.git:
	@echo "==> Cloning whisper-jni $(WHISPER_JNI_TAG) with submodules (whisper.cpp)…"
	@mkdir -p $(BUILD_DIR)
	git clone --depth 1 --branch $(WHISPER_JNI_TAG) \
	    https://github.com/GiviMAD/whisper-jni \
	    $(WHISPER_JNI_DIR)
	# whisper.cpp is a git submodule inside whisper-jni
	cd $(WHISPER_JNI_DIR) && git submodule update --init --recursive

# ── Step 2: Configure with CoreML enabled ─────────────────────────────────────

configure: $(CMAKE_BUILD)/Makefile

$(CMAKE_BUILD)/Makefile: $(WHISPER_JNI_DIR)/.git
	@echo "==> Configuring CMake for $(ARCH) with WHISPER_COREML=ON…"
	@mkdir -p $(CMAKE_BUILD)
	cmake -S $(WHISPER_JNI_DIR)/src/main/cpp \
	      -B $(CMAKE_BUILD) \
	      -DCMAKE_BUILD_TYPE=Release \
	      -DCMAKE_OSX_ARCHITECTURES=$(ARCH) \
	      -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 \
	      -DWHISPER_COREML=ON \
	      -DGGML_NO_I8MM=ON \
	      -DGGML_CUDA=OFF \
	      -DGGML_VULKAN=OFF \
	      -DGGML_KOMPUTE=OFF \
	      -DJAVA_HOME=$(JAVA_HOME) \
	      -DBUILD_SHARED_LIBS=ON

# ── Step 3: Build ─────────────────────────────────────────────────────────────

build: $(CMAKE_BUILD)/Makefile
	@echo "==> Building libwhisperjni-coreml.dylib for $(ARCH) (may take ~5 min)…"
	cmake --build $(CMAKE_BUILD) --config Release -j$(shell sysctl -n hw.logicalcpu)

# ── Step 4: Install into Gradle resources ────────────────────────────────────

install: build
	@echo "==> Installing to $(OUTPUT_DYLIB)…"
	@mkdir -p $(OUTPUT_DIR)
	cp $(CMAKE_BUILD)/libwhisperjni.dylib $(OUTPUT_DYLIB)
	@# Fix install name so System.load() can resolve dependencies at the load path
	install_name_tool -id @rpath/libwhisperjni-coreml.dylib $(OUTPUT_DYLIB)
	@echo "==> Done:"
	@ls -lh $(OUTPUT_DYLIB)
	@echo ""
	@echo "Rebuild the app to pick up the new dylib:"
	@echo "  ./gradlew :composeApp:desktopRun"

# ── Universal binary (arm64 + x86_64) ────────────────────────────────────────

universal:
	@echo "==> Building universal binary (arm64 + x86_64)…"
	$(MAKE) ARCH=arm64
	$(MAKE) ARCH=x86_64
	@mkdir -p $(OUTPUT_DIR)
	lipo -create \
	     $(BUILD_DIR)/whisper-jni/build-coreml-arm64/libwhisperjni.dylib \
	     $(BUILD_DIR)/whisper-jni/build-coreml-x86_64/libwhisperjni.dylib \
	     -output $(OUTPUT_DYLIB)
	install_name_tool -id @rpath/libwhisperjni-coreml.dylib $(OUTPUT_DYLIB)
	@echo "==> Universal binary:"
	@lipo -info $(OUTPUT_DYLIB)
	@ls -lh $(OUTPUT_DYLIB)

# ── Clean ─────────────────────────────────────────────────────────────────────

clean:
	rm -rf $(BUILD_DIR)
	rm -f $(OUTPUT_DYLIB)
	@echo "==> Cleaned."
