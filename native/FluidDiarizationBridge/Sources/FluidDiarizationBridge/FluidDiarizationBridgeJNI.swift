// FluidDiarizationBridgeJNI.swift
//
// JNI bridge between FluidAudioDiarizationBackend.kt and the FluidAudio CoreML framework.
//
// Threading: each @_cdecl function is called on a JNI thread (Dispatchers.Default.limitedParallelism(1)
// from the Kotlin side). Swift async functions are bridged using DispatchSemaphore so the JNI thread
// blocks until the async operation completes. See ADR-002.
//
// Memory: all strings returned by nativeDiarize are standard JVM-managed jstrings created via
// jni_new_string_utf — no manual free needed on the Kotlin side.
//
// FluidAudio API assumptions (verify against the installed SDK version):
//   - OfflineDiarizerManager()          — init, loads CoreML model on first use
//   - .areModelsDownloaded() async -> Bool
//   - .downloadModels() async throws
//   - .process(url: URL, maxSpeakers: Int?) async throws -> [SpeakerSegment]
//   - SpeakerSegment: startTime: Double, endTime: Double, speakerId: String

import Foundation
import FluidAudio
import CJNIBridge

// MARK: - Singleton

/// Holds the OfflineDiarizerManager instance across JNI calls.
/// CoreML model loading is expensive — instantiate once, reuse forever.
private final class BridgeState {
    static let shared = BridgeState()
    let diarizer = OfflineDiarizerManager()
    private init() {}
}

// MARK: - JSON encoding

private struct SegmentJson: Encodable {
    let start: Double
    let end: Double
    let speaker: String
}

private struct ErrorJson: Encodable {
    let error: String
}

private let encoder: JSONEncoder = {
    let e = JSONEncoder()
    e.outputFormatting = []
    return e
}()

private func encodeSegments(_ segments: [SpeakerSegment]) -> String {
    let mapped = segments.map { SegmentJson(start: $0.startTime, end: $0.endTime, speaker: $0.speakerId) }
    guard let data = try? encoder.encode(mapped) else { return "[]" }
    return String(data: data, encoding: .utf8) ?? "[]"
}

private func encodeError(_ message: String) -> String {
    guard let data = try? encoder.encode(ErrorJson(error: message)) else { return "{\"error\":\"unknown\"}" }
    return String(data: data, encoding: .utf8) ?? "{\"error\":\"unknown\"}"
}

// MARK: - JNI exports

// areModelsAvailable
// Kotlin: private external fun nativeAreModelsAvailable(): Boolean
@_cdecl("Java_com_meetingnotes_data_audio_FluidAudioDiarizationBackend_nativeAreModelsAvailable")
public func nativeAreModelsAvailable(
    _ env: UnsafeMutableRawPointer,
    _ obj: UnsafeMutableRawPointer
) -> UInt8 {
    let semaphore = DispatchSemaphore(value: 0)
    var result = false
    Task {
        result = await BridgeState.shared.diarizer.areModelsDownloaded()
        semaphore.signal()
    }
    semaphore.wait()
    return result ? 1 : 0
}

// downloadModels
// Kotlin: private external fun nativeDownloadModels()
@_cdecl("Java_com_meetingnotes_data_audio_FluidAudioDiarizationBackend_nativeDownloadModels")
public func nativeDownloadModels(
    _ env: UnsafeMutableRawPointer,
    _ obj: UnsafeMutableRawPointer
) {
    let semaphore = DispatchSemaphore(value: 0)
    Task {
        try? await BridgeState.shared.diarizer.downloadModels()
        semaphore.signal()
    }
    semaphore.wait()
}

// diarize
// Kotlin: private external fun nativeDiarize(audioFilePath: String, maxSpeakers: Int, timeoutMinutes: Long): String
@_cdecl("Java_com_meetingnotes_data_audio_FluidAudioDiarizationBackend_nativeDiarize")
public func nativeDiarize(
    _ env: UnsafeMutableRawPointer,
    _ obj: UnsafeMutableRawPointer,
    _ audioFilePathJ: UnsafeMutableRawPointer,
    _ maxSpeakers: Int32,
    _ timeoutMinutes: Int64
) -> UnsafeMutableRawPointer? {
    // Extract the Java string before any async work — JNI string references are thread-local.
    guard let cPath = jni_get_string_utf(env, audioFilePathJ) else {
        return jni_new_string_utf(env, encodeError("null audioFilePath"))
    }
    let audioFilePath = String(cString: cPath)
    jni_release_string_utf(env, audioFilePathJ, cPath)

    let audioURL = URL(fileURLWithPath: audioFilePath)
    let maxSpeakersOpt: Int? = maxSpeakers > 0 ? Int(maxSpeakers) : nil
    let timeoutSeconds = Double(timeoutMinutes) * 60.0

    let semaphore = DispatchSemaphore(value: 0)
    var jsonResult = ""

    Task {
        do {
            let segments = try await BridgeState.shared.diarizer.process(
                url: audioURL,
                maxSpeakers: maxSpeakersOpt
            )
            jsonResult = encodeSegments(segments)
        } catch {
            jsonResult = encodeError(error.localizedDescription)
        }
        semaphore.signal()
    }

    let waited = semaphore.wait(timeout: .now() + timeoutSeconds)
    if waited == .timedOut {
        jsonResult = encodeError("Diarization timed out after \(timeoutMinutes) minutes")
    }

    return jni_new_string_utf(env, jsonResult)
}
