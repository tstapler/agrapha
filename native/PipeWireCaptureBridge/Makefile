# Builds libpipewire-jni.so — Linux PipeWire system audio JNI bridge.
#
# Prerequisites: gcc, JDK 17+, libpipewire-0.3-dev, libspa-0.2-dev
# Install on Ubuntu/Debian: sudo apt-get install libpipewire-0.3-dev libspa-0.2-dev
#
# Usage (from native/PipeWireCaptureBridge/):
#   make          -- build libpipewire-jni.so
#   make clean    -- remove build artifacts
#
# Output: libpipewire-jni.so is copied to composeApp/src/desktopMain/resources/
# so Gradle picks it up as a classpath resource for System.load() extraction.

JAVA_HOME ?= $(shell java -XshowSettings:all -version 2>&1 | grep 'java.home' | awk '{print $$3}')

# Fallback: try well-known Linux JDK paths
ifeq ($(JAVA_HOME),)
  JAVA_HOME := $(shell dirname $(shell readlink -f $(shell which java)) 2>/dev/null)/../
endif

OUTPUT_DIR = ../../composeApp/src/desktopMain/resources

CC = gcc

CFLAGS = -shared -fPIC \
         -I$(JAVA_HOME)/include \
         -I$(JAVA_HOME)/include/linux \
         -Ijni \
         -O2

LDFLAGS = -lpthread

# Use pkg-config for PipeWire if available; otherwise fall back to known paths.
PIPEWIRE_CFLAGS := $(shell pkg-config --cflags libpipewire-0.3 2>/dev/null)
PIPEWIRE_LIBS   := $(shell pkg-config --libs libpipewire-0.3 2>/dev/null || echo "-lpipewire-0.3")

.PHONY: all clean

all: $(OUTPUT_DIR)/libpipewire-jni.so

$(OUTPUT_DIR)/libpipewire-jni.so: jni/PipeWireCaptureBridgeJNI.c jni/PipeWireCaptureBridgeJNI.h
	@echo "==> Building libpipewire-jni.so..."
	@mkdir -p $(OUTPUT_DIR)
	$(CC) $(CFLAGS) $(PIPEWIRE_CFLAGS) \
		jni/PipeWireCaptureBridgeJNI.c \
		$(PIPEWIRE_LIBS) $(LDFLAGS) \
		-o $(OUTPUT_DIR)/libpipewire-jni.so
	@echo "==> Done: $(OUTPUT_DIR)/libpipewire-jni.so"
	@ls -lh $(OUTPUT_DIR)/libpipewire-jni.so

clean:
	rm -f $(OUTPUT_DIR)/libpipewire-jni.so
