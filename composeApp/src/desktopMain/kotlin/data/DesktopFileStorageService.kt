package com.meetingnotes.data

import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

actual class FileStorageService actual constructor() {

    private val base: Path = storageBaseDir()

    actual fun ensureDirectoriesExist() {
        base.resolve("db").createDirectories()
        base.resolve("recordings").createDirectories()
        base.resolve("models").createDirectories()
    }

    actual fun getAudioFilePath(meetingId: String): String =
        base.resolve("recordings/$meetingId.wav").absolutePathString()

    actual fun getTranscriptFilePath(meetingId: String): String =
        base.resolve("recordings/$meetingId.json").absolutePathString()

    actual fun getModelsDir(): String =
        base.resolve("models").absolutePathString()

    actual fun saveAudioBytes(meetingId: String, bytes: ByteArray) {
        val path = base.resolve("recordings/$meetingId.wav")
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    actual fun loadAudioBytes(meetingId: String): ByteArray? {
        val path = base.resolve("recordings/$meetingId.wav")
        return if (path.exists()) path.readBytes() else null
    }

    actual fun deleteAudioFile(meetingId: String) {
        base.resolve("recordings/$meetingId.wav").toFile().delete()
    }
}
