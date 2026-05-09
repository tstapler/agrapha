//! Global hotkey support for push-to-talk dictation.
//!
//! Backend selection (tried in order):
//!   1. Wayland `xdg-desktop-portal` GlobalShortcuts (GNOME 46+ / KDE Plasma 6)
//!      — truly global under compositor-native Wayland apps
//!   2. X11 `XGrabKey` on root window (x11rb)
//!      — works on pure X11 sessions AND XWayland Wayland sessions
//!
//! Public API:
//!   - `is_supported()` — true if at least one backend is available
//!   - `register_and_wait(key_code, modifiers, timeout_ms)` — blocks until hotkey fires
//!   - `interrupt()` — unblocks a blocked `register_and_wait`
//!   - `backend_description()` — human-readable status string

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

static INTERRUPT: AtomicBool = AtomicBool::new(false);

// ── Public API ────────────────────────────────────────────────────────────────

pub fn is_supported() -> bool {
    wayland_portal_available() || x11_available()
}

pub fn interrupt() {
    INTERRUPT.store(true, Ordering::SeqCst);
}

/// Blocks until the configured hotkey fires, timeout elapses, or `interrupt()` is called.
///
/// `key_code`: X11 keycode (e.g. 65 = Space). Ignored on Wayland portal path.
/// `modifiers`: X11 ModMask bitmask (e.g. 0x40 = Mod4/Super). Ignored on Wayland portal path.
/// `timeout_ms`: maximum wait in ms; 0 = indefinite.
pub fn register_and_wait(key_code: u8, modifiers: u16, timeout_ms: u64) -> bool {
    INTERRUPT.store(false, Ordering::SeqCst);

    // Prefer Wayland portal when in a Wayland session
    if std::env::var("WAYLAND_DISPLAY").is_ok() && wayland_portal_available() {
        return wayland_wait(timeout_ms);
    }
    if x11_available() {
        return x11_wait(key_code, modifiers, timeout_ms);
    }
    false
}

pub fn backend_description() -> String {
    if std::env::var("WAYLAND_DISPLAY").is_ok() {
        if wayland_portal_available() {
            "Wayland xdg-desktop-portal GlobalShortcuts".to_owned()
        } else if x11_available() {
            "X11 XGrabKey via XWayland (compositor portal unavailable — GNOME 46+/KDE 6+ required)"
                .to_owned()
        } else {
            "Unavailable: Wayland session detected but no portal and no DISPLAY".to_owned()
        }
    } else if x11_available() {
        "X11 XGrabKey".to_owned()
    } else {
        "Unavailable: no DISPLAY or WAYLAND_DISPLAY environment variable".to_owned()
    }
}

// ── X11 backend ───────────────────────────────────────────────────────────────

fn x11_available() -> bool {
    std::env::var("DISPLAY").is_ok()
}

fn x11_wait(key_code: u8, modifiers: u16, timeout_ms: u64) -> bool {
    use x11rb::connection::Connection;
    use x11rb::protocol::xproto::{ConnectionExt, GrabMode, ModMask};
    use x11rb::protocol::Event;
    use x11rb::rust_connection::RustConnection;

    let display = match std::env::var("DISPLAY") {
        Ok(d) => d,
        Err(_) => return false,
    };
    let (conn, screen_num) = match RustConnection::connect(Some(&display)) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[GlobalShortcut/X11] connect failed: {e}");
            return false;
        }
    };
    let root = conn.setup().roots[screen_num].root;

    // Grab the key with common modifier combos so NumLock / CapsLock don't break it
    let base = ModMask::from(modifiers);
    for extra in [
        ModMask::from(0u16),
        ModMask::M2,
        ModMask::LOCK,
        ModMask::M2 | ModMask::LOCK,
    ] {
        let _ = conn.grab_key(false, root, base | extra, key_code, GrabMode::ASYNC, GrabMode::ASYNC);
    }
    let _ = conn.flush();

    let deadline = timeout_deadline(timeout_ms);
    let mut fired = false;

    'poll: loop {
        if INTERRUPT.load(Ordering::SeqCst) {
            break;
        }
        if past_deadline(deadline) {
            break;
        }
        match conn.poll_for_event() {
            Ok(Some(Event::KeyPress(_))) => {
                fired = true;
                break 'poll;
            }
            Ok(Some(_)) => {}
            Ok(None) => std::thread::sleep(Duration::from_millis(20)),
            Err(e) => {
                eprintln!("[GlobalShortcut/X11] poll error: {e}");
                break;
            }
        }
    }

    for extra in [
        ModMask::from(0u16),
        ModMask::M2,
        ModMask::LOCK,
        ModMask::M2 | ModMask::LOCK,
    ] {
        let _ = conn.ungrab_key(key_code, root, base | extra);
    }
    let _ = conn.flush();
    fired
}

// ── Wayland portal backend ────────────────────────────────────────────────────

const PORTAL_DEST: &str = "org.freedesktop.portal.Desktop";
const PORTAL_PATH: &str = "/org/freedesktop/portal/desktop";
const PORTAL_IFACE: &str = "org.freedesktop.portal.GlobalShortcuts";
const SHORTCUT_ID: &str = "agrapha-push-to-talk";

fn wayland_portal_available() -> bool {
    let Ok(conn) = zbus::blocking::Connection::session() else {
        return false;
    };
    let Ok(proxy) = zbus::blocking::Proxy::new(&conn, PORTAL_DEST, PORTAL_PATH, PORTAL_IFACE)
    else {
        return false;
    };
    proxy.get_property::<u32>("version").map(|v| v >= 1).unwrap_or(false)
}

/// Blocks until the Wayland portal fires the push-to-talk shortcut.
///
/// Protocol (xdg-desktop-portal §GlobalShortcuts):
///   1. `CreateSession` → session_handle
///   2. `BindShortcuts(session_handle, shortcuts)` — compositor shows key-assignment UI once
///   3. Receive `Activated` signal on the session object
///
/// The blocking signal iterator runs in a dedicated thread; the calling thread
/// polls a channel with 50 ms granularity so `interrupt()` and timeout work.
fn wayland_wait(timeout_ms: u64) -> bool {
    use std::collections::HashMap;
    use std::sync::mpsc;
    use zbus::zvariant::{OwnedObjectPath, OwnedValue, Value};

    let conn = match zbus::blocking::Connection::session() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[GlobalShortcut/Wayland] D-Bus session failed: {e}");
            return false;
        }
    };

    let proxy =
        match zbus::blocking::Proxy::new(&conn, PORTAL_DEST, PORTAL_PATH, PORTAL_IFACE) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("[GlobalShortcut/Wayland] proxy failed: {e}");
                return false;
            }
        };

    // 1. CreateSession
    let mut create_opts: HashMap<String, Value<'_>> = HashMap::new();
    create_opts.insert("session_handle_token".into(), Value::from("agrapha_session"));

    let session_handle: OwnedObjectPath =
        match proxy.call("CreateSession", &(create_opts,)) {
            Ok(h) => h,
            Err(e) => {
                eprintln!("[GlobalShortcut/Wayland] CreateSession failed: {e}");
                return false;
            }
        };

    // 2. BindShortcuts — describe the push-to-talk shortcut
    let mut shortcut_desc: HashMap<String, Value<'_>> = HashMap::new();
    shortcut_desc.insert("description".into(), Value::from("Dictation push-to-talk"));
    shortcut_desc.insert("preferred_trigger".into(), Value::from("Super+space"));

    let shortcuts: Vec<(String, HashMap<String, Value<'_>>)> =
        vec![(SHORTCUT_ID.to_owned(), shortcut_desc)];

    let bind_opts: HashMap<String, Value<'_>> = HashMap::new();
    // parent_window = "" (empty = no parent; acceptable for XWayland-hosted apps)
    let _: OwnedObjectPath = match proxy.call(
        "BindShortcuts",
        &(session_handle.as_ref(), shortcuts, "", bind_opts),
    ) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("[GlobalShortcut/Wayland] BindShortcuts failed: {e}");
            return false;
        }
    };

    // 3. Subscribe to Activated signal on the session object, then wait in a thread
    let session_proxy = match zbus::blocking::Proxy::new(
        &conn,
        PORTAL_DEST,
        session_handle.as_str(),
        PORTAL_IFACE,
    ) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("[GlobalShortcut/Wayland] session proxy failed: {e}");
            return false;
        }
    };

    let mut signal_iter = match session_proxy.receive_signal("Activated") {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[GlobalShortcut/Wayland] signal subscription failed: {e}");
            return false;
        }
    };

    let (tx, rx) = mpsc::channel::<bool>();

    // Background thread: block on the signal iterator
    std::thread::spawn(move || {
        for msg in &mut signal_iter {
            // Deserialise: (session_handle, shortcut_id, timestamp, options)
            let matched = msg
                .body()
                .deserialize::<(OwnedObjectPath, String, u64, HashMap<String, OwnedValue>)>()
                .map(|(_, id, _, _)| id == SHORTCUT_ID)
                .unwrap_or(false);
            tx.send(matched).ok();
            return;
        }
        tx.send(false).ok();
    });

    // Main thread: poll channel with interrupt and timeout support
    let deadline = timeout_deadline(timeout_ms);
    loop {
        if INTERRUPT.load(Ordering::SeqCst) {
            return false;
        }
        if past_deadline(deadline) {
            return false;
        }
        match rx.recv_timeout(Duration::from_millis(50)) {
            Ok(fired) => return fired,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => return false,
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn timeout_deadline(timeout_ms: u64) -> Option<Instant> {
    if timeout_ms > 0 {
        Some(Instant::now() + Duration::from_millis(timeout_ms))
    } else {
        None
    }
}

fn past_deadline(deadline: Option<Instant>) -> bool {
    deadline.map(|d| Instant::now() >= d).unwrap_or(false)
}
