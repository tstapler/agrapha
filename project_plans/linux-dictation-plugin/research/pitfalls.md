# Pitfalls Research: Linux Support + Dictation Plugin API

## PipeWire: Monitor / Loopback Audio Capture Permissions

**Default permissions:** On a standard PipeWire desktop (Fedora, Ubuntu 22.04+,
Arch), any process running as the logged-in user can connect a PipeWire client
and capture from monitor sources — no extra `sudo` or group membership needed.
PipeWire uses Unix domain sockets in `$XDG_RUNTIME_DIR/pipewire-0`; session
ownership is sufficient.

**Capturing system audio (monitor source):**
Set the PipeWire stream property `PW_KEY_STREAM_CAPTURE_SINK = "true"` when
connecting the stream. This routes the connection to the sink's monitor port
(loopback) rather than a microphone. No separate `pw-loopback` process or
`pactl load-module` is needed for read-only capture.

**Potential pitfall — Flatpak sandboxing:** If Agrapha is ever packaged as a
Flatpak, the PipeWire socket is not exposed by default. Requires the Flatpak
permission `--socket=pulseaudio` (which maps to PipeWire's PulseAudio compat
layer) or `--filesystem=xdg-run/pipewire-0`. Native `.deb`/`.rpm` and AppImage
distributions are unaffected.

**PipeWire not installed:** On older Ubuntu (≤ 21.10) or minimal server installs,
PipeWire may not be running. Detect at runtime:
```kotlin
val pipeWireSocket = File(System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/1000", "pipewire-0")
val pipeWireAvailable = pipeWireSocket.exists()
```

---

## ydotool: Security, Root, and Common Installation Pitfalls

**Root vs. group-based access:**
ydotool requires write access to `/dev/uinput`. By default, this device is
owned by root and mode `0600`. Two paths to non-root access:

1. **udev rule (recommended):**
   ```
   KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"
   ```
   Save to `/etc/udev/rules.d/70-uinput.rules`. Then: `sudo usermod -aG input $USER`,
   log out/in, run `sudo udevadm control --reload`.

2. **Run ydotoold as root** (common distro default but creates socket permission issues).

**Known pitfalls:**

| Issue | Cause | Fix |
|---|---|---|
| `ydotoold` socket permissions 600 | Started as root with setuid, creates socket owned by root | Use `--socket-path` flag or run as `input` group member |
| udev rule not taking effect | systemd 253→254 regression in udev ACL handling | Upgrade systemd, or use `TAG+="uaccess"` in rule |
| `Permission denied /dev/uinput` | User not in `input` group, or udev rule file ordering wrong | Rename rule to `70-uinput.rules` (before `80-uinput.rules`) |
| Works in terminal, fails from Agrapha | App launched before udev rule was reloaded / before login session re-applied ACLs | Full logout + login required after adding udev rule |
| GNOME Ubuntu 24.04 Wayland startup failure | ydotoold socket not set up before app launch | Start ydotoold via systemd user unit or app startup check |

**Agrapha UX recommendation:** At startup, detect ydotool availability:
```kotlin
fun checkYdotool(): TextInjectorStatus {
    val ydotoolExists = ProcessBuilder("which", "ydotool").start().waitFor() == 0
    val daemonRunning = ProcessBuilder("pgrep", "-x", "ydotoold").start().waitFor() == 0
    return when {
        !ydotoolExists  -> TextInjectorStatus.NOT_INSTALLED
        !daemonRunning  -> TextInjectorStatus.DAEMON_NOT_RUNNING
        else            -> TextInjectorStatus.OK
    }
}
```
Show actionable error UI rather than silently failing.

---

## Wayland Global Hotkey: Fundamental Limitations

**Why apps cannot register global hotkeys on Wayland without compositor cooperation:**

Wayland's security model deliberately prevents any client from receiving keyboard
events intended for another window. The compositor has exclusive control over
keyboard event routing. This is by design — it eliminates the X11 keylogger
vulnerability where any app could `XGrabKey` and intercept passwords.

**Practical consequences for push-to-talk:**
- `XGrabKey` / Java `Robot` global key listeners: **do not work** under native Wayland.
- `xdotool key` for key simulation: **does not work** under native Wayland.
- Under XWayland, global key grab only works for windows within XWayland's domain,
  not for native Wayland windows (i.e., the user's browser or terminal).

**Available paths:**
1. **GlobalShortcuts portal** (`org.freedesktop.portal.GlobalShortcuts`): Stable
   in xdg-desktop-portal ≥ 1.16 (GNOME 46+, KDE Plasma 6). Requires D-Bus IPC;
   adds dependency (e.g., `dbus-java-transport-native-unixfd` or JNA dbus bindings).
   User sees a one-time compositor permission prompt.
2. **evdev polling** (raw input): Read `/dev/input/event*` devices; detect keypresses
   without compositor. Requires `input` group. Works everywhere but captures globally
   even when user is in a password field — a security concern to document.
3. **XWayland only**: Falls back to `XGrabKey` when `WAYLAND_DISPLAY` is set but
   app is running under XWayland. Fragile and session-type dependent.

**Recommended minimum viable approach:** Fall back to a Settings-configurable
"press and hold" in-window shortcut that only activates when Agrapha is focused,
with a prominent note that global push-to-talk on Wayland requires the portal.

---

## ClassLoader Isolation: URLClassLoader Leaks and Conflicts

**Memory leak pattern:**
A `URLClassLoader` is garbage-collected only when **all instances of classes it
defined** are GC-eligible. Static fields, registered listeners, thread-local
variables, and JDBC drivers registered with `DriverManager` will keep the
classloader alive indefinitely. This results in `Metaspace`/`PermGen` growth on
plugin reload.

**Root causes to guard against:**
- Plugin registers a shutdown hook → holds classloader reference via closure
- Plugin uses a static `ThreadLocal` → JVM ThreadLocalMap holds class reference
- Plugin calls `ServiceLoader` internally → provider cache holds class reference
- Plugin registers with Java's `LogManager`, `DriverManager`, or annotation caches

**Prevention checklist for `SpeechOutputPlugin` API design:**
1. Define a `fun stop()` / `fun close()` lifecycle method in the SPI — call it
   before closing the classloader.
2. Call `URLClassLoader.close()` after `stop()` to release `.jar` file handles
   (prevents `FileNotFoundException` on plugin update/unload on Linux).
3. Do NOT store plugin instances in `companion object` / `object` singletons of
   the host app — use weak references or scoped holders.
4. Isolate plugin classloaders with child-first delegation if plugins bundle
   their own versions of shared libraries (e.g., different whisper-jni version).

**Version conflict pitfall:** If a plugin bundles `whisper-jni` and the host app
also loads `whisper-jni`, the JNI library (`libwhisper_jni.so`) can only be loaded
once per JVM. Two different `System.load()` calls with different paths for the
same native lib will throw `UnsatisfiedLinkError`. Solution: mark `whisper-jni`
as `compileOnly`/`provided` scope in plugin API; plugins must use the host's
loaded native library.

---

## whisper-jni on Linux: Known Issues

**AVX2 / CPU flag requirement (x86_64 only):**
The bundled `libwhisper_jni.so` for Linux x86_64 is compiled with AVX2 + FMA +
F16C. CPUs older than Intel Haswell (2013) or AMD Excavator (2015) will crash
with `SIGILL` (illegal instruction). The JVM catches this as a native crash, not
a Java exception, so it is not recoverable from Kotlin. Detect before loading:
```kotlin
val flags = File("/proc/cpuinfo").readText()
if (!listOf("avx2","fma","f16c","avx").all { flags.contains(it) }) {
    // show error: CPU too old, provide link to build from source
}
```

**GLIBC version floor:** Built against GLIBC 2.31 (Debian Focal / Ubuntu 20.04).
RHEL 8 ships GLIBC 2.28 — whisper-jni will fail to load with
`version GLIBC_2.31 not found`. Minimum supported distros: Ubuntu 20.04+,
Fedora 32+, Debian Bullseye+. Detect via `ldd --version`.

**libstdc++ dependency:** whisper.cpp is compiled as C++; the bundled `.so`
links against `libstdc++.so.6`. On minimal server installs without a C++ runtime,
add `libstdc++6` to installation prerequisites.

**Thread safety:** `WhisperJNI` is not thread-safe. The existing codebase should
already serialize transcription calls, but plugin implementations using whisper
directly must coordinate with the host's transcription pipeline to avoid
concurrent native context access.

**arm64 (aarch64) notes:** NEON is used instead of AVX; no AVX2 check needed.
The `__fp16` compiler issue (Jetson-specific GCC bug) is not present on standard
aarch64 Linux (Debian, Ubuntu, Fedora ARM). Raspberry Pi 4+ and AWS Graviton 2+
are confirmed working.
