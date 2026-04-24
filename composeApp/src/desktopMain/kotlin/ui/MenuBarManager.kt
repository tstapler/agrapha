package com.meetingnotes.ui

import java.awt.*
import java.awt.image.BufferedImage

/**
 * Manages the macOS menu bar status item using [java.awt.SystemTray].
 *
 * Provides a persistent tray icon with a popup menu for:
 *   - Start / Stop recording
 *   - Show window
 *   - Quit
 */
class MenuBarManager(
    private val onStartRecording: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onShowWindow: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null
    private val startItem = MenuItem("Start Recording")
    private val stopItem = MenuItem("Stop Recording").also { it.isEnabled = false }

    fun install() {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray not supported — menu bar icon unavailable")
            return
        }
        val popup = PopupMenu().apply {
            add(startItem.also { it.addActionListener { onStartRecording() } })
            add(stopItem.also { it.addActionListener { onStopRecording() } })
            addSeparator()
            add(MenuItem("Show Window").also { it.addActionListener { onShowWindow() } })
            addSeparator()
            add(MenuItem("Quit").also { it.addActionListener { onQuit() } })
        }
        val icon = TrayIcon(idleIcon(), "Meeting Notes", popup).also {
            it.isImageAutoSize = true
            it.addActionListener { onShowWindow() }
        }
        try {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        } catch (e: AWTException) {
            System.err.println("Could not install tray icon: ${e.message}")
        }
    }

    fun remove() {
        trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        trayIcon = null
    }

    /** Switch to the "recording" visual state. */
    fun setRecordingState(isRecording: Boolean) {
        trayIcon?.image = if (isRecording) recordingIcon() else idleIcon()
        startItem.isEnabled = !isRecording
        stopItem.isEnabled = isRecording
        trayIcon?.toolTip = if (isRecording) "Meeting Notes — Recording…" else "Meeting Notes"
    }

    /** Show a balloon notification (no-op on macOS — uses Notification Center instead). */
    fun notify(caption: String, text: String) {
        trayIcon?.displayMessage(caption, text, TrayIcon.MessageType.INFO)
    }

    // ── Icons ─────────────────────────────────────────────────────────────────

    private fun idleIcon(): Image = buildIcon(Color(0xFF, 0xFF, 0xFF, 0xCC))
    private fun recordingIcon(): Image = buildIcon(Color(0xFF, 0x40, 0x40, 0xFF))

    private fun buildIcon(color: Color): Image {
        val size = 22
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        // Draw a simple microphone silhouette using a circle + rectangle
        g.fillOval(7, 3, 8, 10)               // capsule body
        g.fillRect(10, 13, 2, 4)               // stand
        g.drawArc(5, 8, 12, 8, 0, -180)       // arc stand
        g.dispose()
        return img
    }
}
