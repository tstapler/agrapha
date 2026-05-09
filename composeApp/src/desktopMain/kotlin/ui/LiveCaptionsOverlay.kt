package com.meetingnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating always-on-top overlay that renders live caption segments.
 *
 * Appears at the bottom-centre of the screen while [liveSegments] is non-empty;
 * disappears automatically when the flow empties (i.e. LIVE_CAPTIONS deactivated).
 * The window is non-focusable so it never steals keyboard focus from the user's
 * active application.
 */
@Composable
fun LiveCaptionsOverlay(liveSegments: StateFlow<List<String>>) {
    val lines by liveSegments.collectAsState()
    if (lines.isEmpty()) return

    val windowState = rememberWindowState(
        width = 800.dp,
        height = 160.dp,
        position = WindowPosition(Alignment.BottomCenter),
    )

    Window(
        onCloseRequest = {},
        state = windowState,
        title = "Live Captions",
        undecorated = true,
        alwaysOnTop = true,
        transparent = true,
        focusable = false,
        resizable = false,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 24.sp,
                    )
                }
            }
        }
    }
}
