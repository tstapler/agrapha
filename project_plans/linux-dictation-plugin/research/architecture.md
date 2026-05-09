# Architecture Research: Linux Support + Dictation Plugin API

## Existing JNI Bridge Pattern — AudioCaptureBridge

### Native (C/Obj-C) Layer
`native/AudioCaptureBridge/jni/AudioCaptureBridgeJNI.m`:

- **Ring buffer:** 10-second float ring buffer (`gRingBuffer[16000 * 10]`) shared
  between the native audio callback and JVM reads. Protected with `os_unfair_lock`
  (macOS-specific spinlock).
- **Callback wiring:** `audioCallback(sampleRate, channelCount, sampleCount, float*)` is
  a C function pointer passed to the Obj-C/Swift `AudioCaptureBridgeObjC` singleton.
  It writes samples into the ring buffer.
- **JNI functions exposed:**
  - `nativeCheckPermission()` → `jboolean`
  - `nativeRequestPermission()` → `jboolean` (blocks up to 30s via dispatch semaphore)
  - `nativeStartCapture(sampleRate: jint)` → `jboolean`
  - `nativeStopCapture()` → `void`
  - `nativeReadBuffer(outBuffer: jfloatArray)` → `jint` (samples actually read)

**PipeWire bridge must replicate:**
- Same 5 JNI function signatures (swap permission model: PipeWire has no
  permission dialog on Linux; `nativeCheckPermission` can return `true` and
  `nativeRequestPermission` can return `true` immediately, or check udev/group).
- Same ring buffer + lock pattern (swap `os_unfair_lock` for `pthread_mutex_t`
  or C11 `mtx_t`).
- Replace `audioCallback` registration with a `pw_stream` `process` event hook.

### Build Layer
`native/AudioCaptureBridge/Makefile`:

- **Output:** `composeApp/src/desktopMain/resources/{AudioCaptureBridgeJNI.dylib, libAudioCaptureBridge.dylib}`
- **Architecture:** detects host arch via `$(shell uname -m)`; supports universal binary via `lipo`
- **Gradle integration:** output goes to `resources/` → Gradle picks it up as classpath resource

**Linux PipeWire Makefile should:**
1. Detect `$(shell uname -s)` == `Linux`
2. Use `gcc`/`clang` with `-shared -fPIC`
3. Pull PipeWire headers: `$(shell pkg-config --cflags libpipewire-0.3)`
4. Output `libPipeWireCaptureBridge.so` to same `resources/` dir
5. Run as a Gradle `Exec` task in `composeApp/build.gradle.kts` (conditional on `os.name.startsWith("Linux")`)

---

## Kotlin JNI Bridge Pattern — ScreenCaptureJniBridge.kt

`composeApp/src/desktopMain/kotlin/audio/ScreenCaptureJniBridge.kt`:

### Load Strategy (to replicate)
```kotlin
fun load() {
    if (loaded) return
    // 1. Fast path: System.loadLibrary (development, explicit -Djava.library.path)
    try { System.loadLibrary("AudioCaptureBridgeJNI"); loaded = true; return }
    catch (_: UnsatisfiedLinkError) { /* fall through */ }
    // 2. Slow path: extract from classpath resource to temp dir, then System.load()
    val tmpDir = Files.createTempDirectory("meeting-notes-jni").toFile()
    extractResource("libAudioCaptureBridge.dylib", tmpDir)  // dependency first
    val jniLib = extractResource("AudioCaptureBridgeJNI.dylib", tmpDir)
    System.load(jniLib.absolutePath)
    loaded = true
}
```

**Linux `PipeWireCaptureBridge.kt` should:**
- Extract only `libPipeWireCaptureBridge.so` (no Swift dep, PipeWire is dynamically
  linked at runtime from system libraries)
- Name the temp dir prefix `"agrapha-pipewire-jni"`
- Check `System.getProperty("os.name").lowercase().startsWith("linux")` before loading

### `external fun` signatures (identical for both platforms):
```kotlin
external fun nativeCheckPermission(): Boolean
external fun nativeRequestPermission(): Boolean
external fun nativeStartCapture(sampleRate: Int): Boolean
external fun nativeStopCapture()
external fun nativeReadBuffer(buffer: FloatArray): Int
```

---

## LlmProviderFactory Pattern — Mirror for PluginLoader

`composeApp/src/desktopMain/kotlin/data/llm/LlmProviderFactory.kt`:

```kotlin
object LlmProviderFactory {
    fun create(settings: AppSettings): LlmProvider = when (settings.llmProvider) {
        LlmProviderEnum.OLLAMA     -> OllamaProvider()
        LlmProviderEnum.OPENAI     -> OpenAiProvider()
        LlmProviderEnum.ANTHROPIC  -> AnthropicProvider()
    }
}
```

This is a simple `when`-expression factory. For `PluginLoader`, the pattern
should scale to dynamic discovery rather than a static enum:

```kotlin
object PluginLoader {
    private var pluginClassLoader: URLClassLoader? = null

    fun loadAll(pluginDir: File): List<SpeechOutputPlugin> {
        val urls = pluginDir.listFiles { f -> f.extension == "jar" }
            ?.map { it.toURI().toURL() }?.toTypedArray() ?: return emptyList()
        val cl = URLClassLoader(urls, Thread.currentThread().contextClassLoader)
            .also { pluginClassLoader = it }
        return ServiceLoader.load(SpeechOutputPlugin::class.java, cl).toList()
    }

    fun unload() {
        pluginClassLoader?.close()
        pluginClassLoader = null
    }
}
```

Key difference from `LlmProviderFactory`: enum-based dispatch → ServiceLoader
discovery, requiring explicit classloader lifecycle management.

---

## Platform Detection at Runtime

```kotlin
val osName = System.getProperty("os.name").lowercase()
val isMacOs = osName.startsWith("mac")
val isLinux = osName.startsWith("linux")
val isWayland = System.getenv("WAYLAND_DISPLAY") != null
val isX11    = System.getenv("DISPLAY") != null
```

**Recommended abstraction — `Platform.kt` in `desktopMain`:**
```kotlin
object Platform {
    enum class Os { MACOS, LINUX, WINDOWS, UNKNOWN }
    val current: Os = when {
        System.getProperty("os.name").lowercase().startsWith("mac")     -> Os.MACOS
        System.getProperty("os.name").lowercase().startsWith("linux")   -> Os.LINUX
        System.getProperty("os.name").lowercase().startsWith("windows") -> Os.WINDOWS
        else -> Os.UNKNOWN
    }
    val isWayland: Boolean get() = System.getenv("WAYLAND_DISPLAY") != null
}
```

---

## Injection Point in RecordingSessionManager

`composeApp/src/desktopMain/kotlin/audio/RecordingSessionManager.kt`:

The system audio channel is hard-coded to `ScreenCaptureJniBridge`:
```kotlin
// Line 102-108 in startRecording():
captureStarted = ScreenCaptureJniBridge.nativeStartCapture(16_000)
// ...
val n = ScreenCaptureJniBridge.nativeReadBuffer(buf)
```

**Recommended change:** Introduce a `SystemAudioBackend` interface and inject it:

```kotlin
interface SystemAudioBackend {
    fun checkPermission(): Boolean
    fun requestPermission(): Boolean
    fun startCapture(sampleRate: Int): Boolean
    fun stopCapture()
    fun readBuffer(buffer: FloatArray): Int
}

// Factory (desktopMain):
object SystemAudioBackendFactory {
    fun create(): SystemAudioBackend = when (Platform.current) {
        Platform.Os.MACOS -> ScreenCaptureBackend()   // wraps ScreenCaptureJniBridge
        Platform.Os.LINUX -> PipeWireCaptureBackend() // wraps PipeWireCaptureBridge
        else              -> SilentAudioBackend()      // returns silence, no crash
    }
}
```

Inject `SystemAudioBackend` as a constructor parameter of `RecordingSessionManager`
(already takes `repository` and `storage`). This preserves "macOS untouched" by
not changing `ScreenCaptureJniBridge` — only the manager's dispatch changes.

**No changes needed in `commonMain`** — the interface lives in `desktopMain`.
Plugin SPI interfaces (`SpeechOutputPlugin`) go in `commonMain` so they can be
referenced from shared code, but implementations stay in `desktopMain`.
