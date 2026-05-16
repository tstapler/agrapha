# Builds libwhisperjni-cuda.so — CUDA-accelerated JNI .so for WhisperService on Linux.
#
# WhisperService.loadLibraryOnce() automatically discovers this file at runtime; no
# code changes are needed after running `make`. Falls back to CPU if the file is absent.
#
# Prerequisites:
#   CUDA toolkit (nvcc):
#     Manjaro/Arch:  sudo pacman -S cuda
#     Ubuntu/Debian: sudo apt install nvidia-cuda-toolkit
#     Or via: https://developer.nvidia.com/cuda-downloads
#
#   JDK 17+, CMake 3.21+, git
#
# Usage (from native/WhisperCUDA/):
#   make                     -- clone, build, install
#   make INSTALL_DIR=/tmp    -- override install directory
#   make clean               -- remove build dir (does NOT remove installed .so)
#
# Output: libwhisperjni-cuda.so → ~/.local/share/meeting-notes/lib/
#
# To verify it was picked up, look for this line in WhisperService output:
#   [WhisperService] backend=CUDA

WHISPER_JNI_TAG ?= v1.6.1   # must match libs.versions.toml whisper-jni version
# Default: bundle into the app (same pattern as native/WhisperCoreML/Makefile).
# Override for local-only install: make INSTALL_DIR=~/.local/share/meeting-notes/lib
INSTALL_DIR     ?= ../../composeApp/src/desktopMain/resources

JAVA_HOME       ?= $(shell java -XshowSettings:all -version 2>&1 | grep 'java\.home' | awk '{print $$3}')
BUILD_DIR        = build
WHISPER_JNI_DIR  = $(BUILD_DIR)/whisper-jni
CMAKE_BUILD      = $(WHISPER_JNI_DIR)/build-cuda
OUTPUT_SO        = $(INSTALL_DIR)/libwhisperjni-cuda.so

.PHONY: all clone configure build install clean check-nvcc

all: check-nvcc clone configure build install

check-nvcc:
	@which nvcc > /dev/null 2>&1 || { \
	    echo ""; \
	    echo "ERROR: nvcc not found. Install the CUDA toolkit first:"; \
	    echo "  Manjaro/Arch:  sudo pacman -S cuda"; \
	    echo "  Ubuntu/Debian: sudo apt install nvidia-cuda-toolkit"; \
	    echo "  Then add to PATH: export PATH=/opt/cuda/bin:\$$PATH  (or /usr/local/cuda/bin)"; \
	    echo ""; \
	    exit 1; \
	}
	@echo "==> nvcc: $$(nvcc --version | grep 'release')"
	@echo "==> JAVA_HOME: $(JAVA_HOME)"

# ── Step 1: Clone whisper-jni at the version matching the Maven dependency ────

clone: $(WHISPER_JNI_DIR)/.git

$(WHISPER_JNI_DIR)/.git:
	@echo "==> Cloning whisper-jni $(WHISPER_JNI_TAG) with submodules (whisper.cpp)…"
	@mkdir -p $(BUILD_DIR)
	git clone --depth 1 --branch $(WHISPER_JNI_TAG) \
	    https://github.com/GiviMAD/whisper-jni \
	    $(WHISPER_JNI_DIR)
	cd $(WHISPER_JNI_DIR) && git submodule update --init --recursive

# ── Step 2: Configure with CUDA enabled ──────────────────────────────────────

configure: $(CMAKE_BUILD)/Makefile

$(CMAKE_BUILD)/Makefile: $(WHISPER_JNI_DIR)/.git
	@echo "==> Configuring CMake with GGML_CUDA=ON…"
	@mkdir -p $(CMAKE_BUILD)
	JAVA_HOME=$(JAVA_HOME) cmake -S $(WHISPER_JNI_DIR) \
	      -B $(CMAKE_BUILD) \
	      -DCMAKE_BUILD_TYPE=Release \
	      -DGGML_CUDA=ON \
	      -DGGML_VULKAN=OFF \
	      -DGGML_KOMPUTE=OFF \
	      -DJAVA_HOME=$(JAVA_HOME) \
	      -DJAVA_INCLUDE_PATH=$(JAVA_HOME)/include \
	      -DJAVA_INCLUDE_PATH2=$(JAVA_HOME)/include/linux \
	      -DBUILD_SHARED_LIBS=ON

# ── Step 3: Build ─────────────────────────────────────────────────────────────

build: $(CMAKE_BUILD)/Makefile
	@echo "==> Building libwhisperjni-cuda.so (first run ~10 min due to CUDA compilation)…"
	cmake --build $(CMAKE_BUILD) --config Release -j$(shell nproc)

# ── Step 4: Install to the path WhisperService probes ────────────────────────

install: build
	@echo "==> Installing to $(OUTPUT_SO)…"
	@mkdir -p $(INSTALL_DIR)
	cp $(CMAKE_BUILD)/libwhisper-jni.so $(OUTPUT_SO)
	@echo "==> Done:"
	@ls -lh $(OUTPUT_SO)
	@echo ""
	@echo "Rebuild the app to bundle the CUDA backend:"
	@echo "  ./gradlew :composeApp:desktopRun"
	@echo "Look for: [WhisperService] backend=CUDA"

# ── Clean ─────────────────────────────────────────────────────────────────────

clean:
	rm -rf $(BUILD_DIR)
	@echo "==> Build dir cleaned. Installed .so at $(OUTPUT_SO) was NOT removed."
	@echo "==> To uninstall: rm $(OUTPUT_SO)"
