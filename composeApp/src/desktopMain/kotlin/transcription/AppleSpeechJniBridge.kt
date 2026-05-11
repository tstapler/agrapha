package com.meetingnotes.transcription

/**
 * JNI bridge to the macOS SFSpeechRecognizer implementation in libagrapha_native.dylib.
 *
 * All native methods throw [UnsatisfiedLinkError] on Linux/Windows because the dylib
 * is not loaded there. The safe wrappers ([isAvailable], [requestAuthorization]) return
 * false instead of throwing; only [transcribe] propagates errors to the caller.
 */
internal object AppleSpeechJniBridge {

    /** Returns false on non-macOS platforms without throwing. */
    fun isAvailable(): Boolean = runCatching { nativeIsAvailable() }.getOrDefault(false)

    /**
     * Request speech recognition authorization from the user.
     * Blocks until the macOS permission dialog is dismissed.
     * Returns false if denied or on non-macOS platforms.
     */
    fun requestAuthorization(): Boolean =
        runCatching { nativeRequestAuthorization() }.getOrDefault(false)

    /**
     * Transcribe a WAV or AIFF file using SFSpeechRecognizer.
     * Returns a JSON array: `[{"text":"…","start_ms":0,"end_ms":1200}, …]`
     *
     * @throws RuntimeException on recognition failure or authorization denial
     * @throws UnsatisfiedLinkError on non-macOS platforms
     */
    fun transcribe(audioPath: String): String = nativeTranscribe(audioPath)

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeRequestAuthorization(): Boolean
    private external fun nativeTranscribe(audioPath: String): String
}
