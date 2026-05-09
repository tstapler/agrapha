package com.meetingnotes.dictation

/**
 * [TextInjector] that auto-selects the first available candidate from [candidates].
 *
 * Default order: [YdotoolTextInjector] first (Wayland + X11), then [XdotoolTextInjector]
 * (X11 / XWayland only). The selection is cached after the first call to [isAvailable] or
 * [inject] so the subprocess health-check is not repeated on every keystroke.
 *
 * @param candidates List of injectors to try in order. Injectable for tests.
 */
class AutoDetectTextInjector(
    private val candidates: List<TextInjector> = listOf(
        YdotoolTextInjector(),
        XdotoolTextInjector(),
    ),
) : TextInjector {

    @Volatile private var selected: TextInjector? = null
    @Volatile private var detectionDone = false

    override fun checkStatus(): TextInjector.Status {
        val s = resolveCandidate()
        return s?.checkStatus() ?: TextInjector.Status.NOT_INSTALLED
    }

    override fun isAvailable(): Boolean = resolveCandidate() != null

    override fun inject(text: String): Result<Unit> {
        val injector = resolveCandidate()
            ?: return Result.failure(
                TextInjectorUnavailableException(
                    "No text injector available. Install ydotool (Wayland/X11) or xdotool (X11)."
                )
            )
        return injector.inject(text)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun resolveCandidate(): TextInjector? {
        if (detectionDone) return selected
        synchronized(this) {
            if (detectionDone) return selected
            val found = candidates.firstOrNull { it.isAvailable() }
            selected = found
            detectionDone = true
            if (found != null) {
                System.err.println("[AutoDetectTextInjector] selected: ${found::class.simpleName}")
            } else {
                System.err.println("[AutoDetectTextInjector] no injector available")
            }
        }
        return selected
    }
}
