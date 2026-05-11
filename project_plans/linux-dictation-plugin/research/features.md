# Features Research: Linux Support + Dictation Plugin API

## ydotool — Text Injection (Wayland + X11)

**How it works:** Uses the Linux kernel `uinput` module to emulate a virtual
input device, making it compositor-agnostic (works on X11, Wayland, even
framebuffer). No X server dependency.

**Command syntax:**
```bash
ydotool type "Hello, world!"          # type a literal string
ydotool type --key-delay 12 "text"    # 12ms inter-key delay (default: 12ms)
ydotool type --file /tmp/text.txt     # read from file
```

**Daemon requirement:** Since v1.0.0 (stable releases), `ydotoold` daemon **must
be running**. It holds the persistent virtual uinput device and ydotool IPC-
connects to it via a Unix socket at `/tmp/.ydotool_socket`.

**Check if daemon is running:**
```kotlin
fun isYdotooldRunning(): Boolean {
    val result = ProcessBuilder("pgrep", "-x", "ydotoold").start().waitFor()
    return result == 0
    // OR: check socket existence
    // return File("/tmp/.ydotool_socket").exists()
}
```

**Udev rules (required for non-root):**
```
# /etc/udev/rules.d/70-uinput.rules
KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"
```
User must be in the `input` group: `sudo usermod -aG input $USER` then log out.

**Subprocess invocation from Kotlin:**
```kotlin
ProcessBuilder("ydotool", "type", "--", text).start().waitFor()
```

---

## xdotool — Text Injection (X11 only)

**X11-only:** xdotool talks directly to the X server; it **does not work under
native Wayland**. Under XWayland it functions but only types into XWayland
windows, not native Wayland windows.

**Command syntax:**
```bash
xdotool type "Hello, world!"
xdotool type --clearmodifiers "text"   # clear modifier keys first
xdotool type --delay 50 "text"         # 50ms inter-key delay
```

**Wayland fallback options:**
- `wtype` — the canonical Wayland equivalent for keystroke injection; uses the
  `zwp_virtual_keyboard_v1` Wayland protocol. Works on compositors that
  implement `wlr-virtual-keyboard` (Sway, Hyprland, labwc). **Does not work
  on GNOME Wayland** (GNOME does not implement this protocol for security reasons).
- `ydotool` — preferred cross-platform choice (covers X11 + all Wayland
  compositors) at the cost of a daemon and uinput permissions.

**Detection logic for `AutoDetectTextInjector`:**
```kotlin
val isWayland = System.getenv("WAYLAND_DISPLAY") != null
val isX11     = System.getenv("DISPLAY") != null
// Priority: ydotool (universal) > xdotool (X11 fallback) > wtype (wlroots only)
```

---

## Global Hotkeys on Linux

### Wayland

**Fundamental limitation:** Wayland compositors prevent applications from
registering global keyboard grabs for security reasons. No app can intercept
keystrokes destined for another window without compositor cooperation.

**Available mechanisms in 2026:**

| Mechanism | Status | Compositor support |
|---|---|---|
| `xdg-desktop-portal` GlobalShortcuts portal | Stable in portal v1.16+ | GNOME 46+, KDE Plasma 6, Hyprland |
| `wlr-protocols` (Hyprland/Sway raw protocol) | Not standardized | wlroots compositors only |
| `evdev`/`uinput` via raw `/dev/input` read | Works but requires root or `input` group | All |

**GlobalShortcuts portal (recommended path):**
The D-Bus interface `org.freedesktop.portal.GlobalShortcuts` lets apps register
shortcut sessions. The compositor shows the user a permission dialog. This is
the only cross-compositor standard. However, it requires Java D-Bus bindings
(e.g., `dbus-java` or JNA + dbus) — adds a dependency.

**Practical fallback: evdev polling** — read from `/dev/input/event*` with uinput
group membership. More portable but requires careful device enumeration.

### X11

`XGrabKey` via `java.awt.Robot` or JNA/JNI binding to Xlib. Standard approach,
works reliably. Can be implemented with `Toolkit.getDefaultToolkit()` global key
listener trick or explicit `XGrabKey` JNA call.

**Recommended architecture for push-to-talk:**
- Detect session type: `System.getenv("XDG_SESSION_TYPE")` → `"wayland"` or `"x11"`
- Wayland: use GlobalShortcuts portal (requires portal v1.16) with evdev fallback
- X11: use `XGrabKey` via JNA
- Both: expose a `GlobalHotkeyProvider` interface with platform-specific impls

---

## java.util.ServiceLoader — External Plugin Loading

**Standard usage with external JARs:**
```kotlin
val pluginDir = File(System.getProperty("user.home"), ".agrapha/plugins")
val urls = pluginDir.listFiles { f -> f.extension == "jar" }
    ?.map { it.toURI().toURL() }?.toTypedArray() ?: emptyArray()
val loader = URLClassLoader(urls, Thread.currentThread().contextClassLoader)
val plugins = ServiceLoader.load(SpeechOutputPlugin::class.java, loader)
```

**Registration:** Plugin JARs must contain
`META-INF/services/com.meetingnotes.plugin.SpeechOutputPlugin` with the fully
qualified implementation class name.

**Security considerations:**
- ServiceLoader runs in the **caller's security context** — plugins are trusted
  code once loaded; no sandbox is applied.
- URLClassLoader uses **parent-first delegation** by default. Override
  `loadClass()` with child-first if plugin JARs might bundle conflicting
  versions of shared libraries.
- Loaded classes pin the classloader in memory for the JVM's lifetime unless
  explicitly closed: call `loader.close()` when unloading a plugin. URLClassLoader
  implements `Closeable` since Java 7.

---

## Compose Desktop on Linux — Wayland / XWayland Status

**Current state (2026):** Compose Desktop uses Skiko (Skia JVM bindings) which
depends on AWT for windowing. AWT on Linux uses X11 (via `java.awt`) or falls
through to XWayland under Wayland sessions.

**Practical behavior:**
- Under a Wayland compositor with XWayland enabled: Compose Desktop works via
  XWayland — window appears, rendering is correct, but is technically an X11
  client.
- Native Wayland Skiko backend: the JetBrains Runtime (JBR) has a Wayland
  backend but Skiko must be recompiled against it; not available in standard
  distribution.
- **JetBrains IDE products** announced "Wayland by default" for 2026.1 EAP
  (February 2026), suggesting JBR Wayland support is maturing — but Agrapha
  bundles its own JDK and would need to opt in.

**Recommendation:** Target XWayland as baseline; rely on `WAYLAND_DISPLAY` env
var to detect session type for auxiliary features (hotkeys, injection), not for
rendering.

**Known issues under XWayland:**
- HiDPI: XWayland renders at integer scale then compositor up-scales → blur on
  fractional scaling setups (125%, 150%).
- Screen-share / portal APIs: must go through `xdg-desktop-portal` + PipeWire
  (same as screen audio capture).
- Window focus detection for dictation (knowing which app is focused) is
  unreliable across the X11/Wayland boundary.
