# Stack Research: Linux Support + Dictation Plugin API

## whisper-jni Native Library Coverage

**Bundled platforms (Maven artifact `io.github.givimad:whisper-jni`):**
- `linux-x86_64` (GLIBC ≥ 2.31, built on Debian Focal) — confirmed bundled
- `linux-aarch64` (arm64) — confirmed bundled in same JAR
- `darwin-x86_64`, `darwin-aarch64` (macOS Intel + Apple Silicon)
- `windows-x86_64`

All native `.so`/`.dylib`/`.dll` files are extracted at runtime from the JAR by
`WhisperJNI.loadLibrary()` using an internal temp-directory extraction mechanism.
No separate `System.load()` call is required for the bundled build.

**Property override:** Set `io.github.givimad.whisperjni.libdir` to a directory
containing hand-built `libwhisper_jni.so` + `libwhisper.so` if you need a
custom build (e.g., GPU-accelerated or distro-packaged whisper.cpp).

**Current version:** 1.7.1 (wraps whisper.cpp 1.7.1). Earlier 1.4.x versions are
on Maven Central too but lack some Linux fixes.

---

## CPU Requirements on Linux x86_64

The bundled Linux x86_64 binary requires: **AVX2, FMA, F16C, AVX** CPU flags.
Check at runtime with:
```kotlin
val cpuFlags = File("/proc/cpuinfo").readText()
val supported = listOf("avx2","fma","f16c","avx").all { cpuFlags.contains(it) }
```
Older CPUs (pre-Haswell, ~2013) will throw `SIGILL` or fail to load; the app
should detect this and display a friendly error rather than crashing.

The arm64 build does **not** require AVX2 (uses NEON/ARM_FMA instead). There was
a historical `__fp16` compiler issue on Jetson NX (Jetson-specific GCC), but
standard aarch64 Linux (Raspberry Pi 4+, AWS Graviton) is unaffected.

---

## Gradle / JVM Configuration for Linux

whisper-jni's `WhisperJNI.loadLibrary()` handles extraction internally. No
explicit `System.load()` is needed unless using a custom build. However, the
existing `ScreenCaptureJniBridge` extraction pattern (extract resource → temp
dir → `System.load()`) should be reused for the **PipeWire JNI bridge**, which
is not bundled in any Maven artifact.

For the PipeWire bridge `.so`, follow the same pattern as
`ScreenCaptureJniBridge.kt`:
1. Bundle `libPipeWireCaptureBridge.so` under `src/desktopMain/resources/`
2. At init time, extract to a temp directory via `getResourceAsStream`
3. Call `System.load(extractedPath)` — no `System.loadLibrary` fallback needed
   on Linux because `java.library.path` is rarely set to the resources dir.

Gradle resource bundling: add the `.so` to `src/desktopMain/resources/` and
Gradle will package it into the distribution JAR automatically (same as the
existing `.dylib` files).

---

## PipeWire C API — Headers and Key Functions

**Required headers:**
```c
#include <pipewire/pipewire.h>       // pw_init, pw_main_loop_*, pw_stream_*
#include <spa/param/audio/format-utils.h>  // spa_format_audio_raw_parse, spa_format_audio_raw_build
#include <spa/param/audio/raw.h>     // SPA_AUDIO_FORMAT_F32, spa_audio_info_raw
```

**Minimal capture loop pattern** (mirrors `audio-capture.c` from PipeWire docs):
```c
// 1. Initialize
pw_init(NULL, NULL);
struct pw_main_loop *loop = pw_main_loop_new(NULL);
struct pw_context   *ctx  = pw_context_new(pw_main_loop_get_loop(loop), NULL, 0);
struct pw_core      *core = pw_context_connect(ctx, NULL, 0);

// 2. Create INPUT stream
struct pw_stream *stream = pw_stream_new(core, "agrapha-capture",
    pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio",
        PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, "Communication",
        PW_KEY_STREAM_CAPTURE_SINK, "true",  // capture monitor/loopback
        NULL));

// 3. Build audio format
uint8_t buf[1024];
struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buf, sizeof(buf));
struct spa_audio_info_raw info = {
    .format   = SPA_AUDIO_FORMAT_F32,
    .rate     = 16000,
    .channels = 1,
};
const struct spa_pod *params[1] = { spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info) };

// 4. Connect
pw_stream_connect(stream, PW_DIRECTION_INPUT, PW_ID_ANY,
    PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS,
    params, 1);

pw_main_loop_run(loop);
```

**Key insight:** Set `PW_KEY_STREAM_CAPTURE_SINK = "true"` to tap the monitor
port of the default sink — this is the loopback/system-audio equivalent on
PipeWire. No extra `pw-loopback` process is needed for capture-only use cases.

---

## JNI Compilation Flags: Linux vs macOS

| Aspect | macOS | Linux |
|---|---|---|
| Compiler | `clang -ObjC` (Obj-C bridge) | `gcc` or `clang` (pure C) |
| Shared lib flag | `-dynamiclib` | `-shared` |
| PIC flag | implicit on macOS | `-fPIC` (required) |
| Output extension | `.dylib` | `.so` |
| JNI includes | `-I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin` | `-I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux` |
| rpath | `-Wl,-rpath,@loader_path` | `-Wl,-rpath,'$$ORIGIN'` |
| Link dependencies | `-framework Foundation` + Swift dylib | `-lpipewire-0.3` (pkg-config) |

**Example Linux Makefile snippet:**
```makefile
JAVA_HOME ?= $(shell java -XshowSettings:all -version 2>&1 | grep java.home | awk '{print $$3}')
CFLAGS = -fPIC $(shell pkg-config --cflags libpipewire-0.3) \
         -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
LDFLAGS = -shared -Wl,-rpath,'$$ORIGIN' \
          $(shell pkg-config --libs libpipewire-0.3)

libPipeWireCaptureBridge.so: jni/PipeWireCaptureBridgeJNI.c
	$(CC) $(CFLAGS) $(LDFLAGS) $< -o $(OUTPUT_DIR)/$@
```

---

## Linux System Library Dependencies

For the PipeWire bridge:
- `libpipewire-0.3` — the main PipeWire client library (package: `libpipewire-0.3-dev`)
- `libspa-0.2` — bundled with PipeWire; provides SPA audio format utilities
- No extra runtime deps for whisper-jni itself (statically links whisper.cpp)

PipeWire is available by default on Fedora 34+, Ubuntu 22.04+, Arch. Older
distros (Ubuntu 20.04) have it but may lack some API surface; the GLIBC 2.31
requirement for whisper-jni means Ubuntu 20.04 (GLIBC 2.31) is the minimum
supported anyway.
