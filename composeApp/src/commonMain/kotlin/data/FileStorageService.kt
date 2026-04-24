package com.meetingnotes.data

/**
 * Platform-specific file storage abstraction.
 *
 * Implementations create and manage:
 *   ~/.local/share/meeting-notes/
 *       db/           – SQLite database
 *       recordings/   – dual-channel WAV files (one per meeting)
 *       models/       – Whisper GGML model files
 */
expect class FileStorageService() {

    /** Create all required subdirectories if they don't already exist. */
    fun ensureDirectoriesExist()

    /** Absolute path to the WAV file for [meetingId]. File may not yet exist. */
    fun getAudioFilePath(meetingId: String): String

    /** Absolute path to the JSON transcript for [meetingId]. File may not yet exist. */
    fun getTranscriptFilePath(meetingId: String): String

    /** Absolute path to the models directory. */
    fun getModelsDir(): String

    /**
     * Write [pcmData] (Float32 interleaved) to the recording file for [meetingId].
     * Creates parent directories as needed.
     */
    fun saveAudioBytes(meetingId: String, bytes: ByteArray)

    /**
     * Read the raw bytes of the audio file for [meetingId].
     * Returns null if the file does not exist.
     */
    fun loadAudioBytes(meetingId: String): ByteArray?

    /**
     * Delete the audio (WAV) file for [meetingId] to reclaim disk space.
     * No-op if the file does not exist.
     */
    fun deleteAudioFile(meetingId: String)
}
