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
// FluidAudio v0.14.5 API:
//   - OfflineDiarizerManager(config:)        — init; config holds speaker-count constraints
//   - .prepareModels() async throws           — downloads + compiles CoreML models on first call
//   - .process(_ url: URL) async throws -> DiarizationResult
//   - DiarizationResult.segments: [TimedSpeakerSegment]
//   - TimedSpeakerSegment: startTimeSeconds: Float, endTimeSeconds: Float, speakerId: String

import Foundation
import FluidAudio
import CJNIBridge

// MARK: - Singleton

/// Holds the default OfflineDiarizerManager for model caching.
/// CoreML model loading is expensive — instantiate once and reuse.
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

private func encodeSegments(_ segments: [TimedSpeakerSegment]) -> String {
    let mapped = segments.map {
        SegmentJson(start: Double($0.startTimeSeconds), end: Double($0.endTimeSeconds), speaker: $0.speakerId)
    }
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
// Checks whether the offline diarizer model cache exists on disk (no download triggered).
@_cdecl("Java_com_meetingnotes_data_audio_FluidAudioDiarizationBackend_nativeAreModelsAvailable")
public func nativeAreModelsAvailable(
    _ env: UnsafeMutableRawPointer,
    _ obj: UnsafeMutableRawPointer
) -> UInt8 {
    let dir = OfflineDiarizerModels.defaultModelsDirectory()
    var isDir: ObjCBool = false
    let exists = FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDir)
    // Consider models available if the directory exists and is non-empty.
    if exists && isDir.boolValue {
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return contents.isEmpty ? 0 : 1
    }
    return 0
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
        try? await BridgeState.shared.diarizer.prepareModels()
        semaphore.signal()
    }
    semaphore.wait()
}

// diarize
// Kotlin: private external fun nativeDiarize(audioFilePath: String, maxSpeakers: Int, timeoutMinutes: Long): String
//
// maxSpeakers > 0 → configure clustering.maxSpeakers; 0 → auto-detect.
// A new OfflineDiarizerManager is created per call when maxSpeakers differs from default so
// that CoreML clustering constraints are applied correctly. Model files are already on disk
// after nativeDownloadModels(), so the per-call prepareModels() load is fast (no HTTP fetch).
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
    let timeoutSeconds = Double(timeoutMinutes) * 60.0

    let diarizer: OfflineDiarizerManager
    if maxSpeakers > 0 {
        var config = OfflineDiarizerConfig.default
        config.clustering.maxSpeakers = Int(maxSpeakers)
        diarizer = OfflineDiarizerManager(config: config)
    } else {
        diarizer = BridgeState.shared.diarizer
    }

    let semaphore = DispatchSemaphore(value: 0)
    var jsonResult = ""

    Task {
        do {
            let result = try await diarizer.process(audioURL)
            jsonResult = encodeSegments(result.segments)
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
