package com.meetingnotes.plugin

import kotlinx.serialization.Serializable

/**
 * The set of modes a [SpeechOutputPlugin] can operate in.
 *
 * Placed in commonMain so plugin JARs compile against this shared definition.
 */
@Serializable
enum class DictationMode {
    /** Hold a hotkey, speak, release — transcribed text is injected at the cursor. */
    PUSH_TO_TALK,

    /** Transcribe an audio file to stdout or a configured output path. */
    FILE_TRANSCRIPTION,

    /** Always-on mic listener; streams live captions to a floating overlay window. */
    LIVE_CAPTIONS,
}
