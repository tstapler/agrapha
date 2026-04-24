import Foundation
import ScreenCaptureKit
import AVFoundation
import CoreGraphics

/// Obj-C-compatible Swift wrapper around ScreenCaptureKit SCStream for system audio capture.
/// Exposes a C-compatible callback interface suitable for JNI consumption.
// The @objc rename makes the Obj-C runtime name match AudioCaptureBridge.h's @interface declaration.
@objc(AudioCaptureBridgeObjC) public class AudioCaptureBridge: NSObject, SCStreamOutput, SCStreamDelegate {

    // MARK: - Types

    /// PCM audio callback: invoked on each audio buffer.
    /// - Parameters: sampleRate, channelCount, sampleCount, samples (Float32 interleaved)
    public typealias AudioCallback = @convention(c) (
        _ sampleRate: Int32,
        _ channelCount: Int32,
        _ sampleCount: Int32,
        _ samples: UnsafePointer<Float32>
    ) -> Void

    // MARK: - State

    private var stream: SCStream?
    private var audioCallback: AudioCallback?
    private let callbackQueue = DispatchQueue(label: "com.meeting-notes.audio-capture", qos: .userInitiated)

    // MARK: - Public API

    /// Singleton instance shared with JNI bridge.
    @objc public static let shared = AudioCaptureBridge()

    /// Synchronous preflight check — returns true if screen recording permission is already
    /// granted WITHOUT showing a dialog. Safe to call at any time.
    @objc public func checkPermission() -> Bool {
        return CGPreflightScreenCaptureAccess()
    }

    /// Trigger the TCC permission dialog by enumerating shareable content.
    /// Must be called before startCapture. Returns true if permission was already granted.
    @objc public func requestPermission(completion: @escaping (Bool) -> Void) {
        if #available(macOS 13.0, *) {
            SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: false) { content, error in
                if let error = error {
                    NSLog("[AudioCaptureBridge] Permission error: %@", error.localizedDescription)
                    completion(false)
                    return
                }
                NSLog("[AudioCaptureBridge] Permission granted; found %d applications", content?.applications.count ?? 0)
                completion(true)
            }
        } else {
            NSLog("[AudioCaptureBridge] ScreenCaptureKit requires macOS 13+")
            completion(false)
        }
    }

    /// Start system audio capture. Calls audioCallback on each PCM buffer.
    /// - Parameters:
    ///   - sampleRate: Requested sample rate (pass 16000 for Whisper-compatible output)
    ///   - callback: C function pointer invoked on each audio buffer (Float32 mono)
    /// - Returns: true if capture started successfully
    @objc public func startCapture(sampleRate: Int32, callback: AudioCallback?) -> Bool {
        guard #available(macOS 13.0, *) else {
            NSLog("[AudioCaptureBridge] startCapture requires macOS 13+")
            return false
        }

        self.audioCallback = callback

        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = Int(sampleRate)
        config.channelCount = 1  // mono; simpler for Whisper
        config.excludesCurrentProcessAudio = false

        // We need a filter — capture all audio (no specific app filter)
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: false) { [weak self] content, error in
            guard let self = self else { return }
            guard let content = content, error == nil else {
                NSLog("[AudioCaptureBridge] Failed to get shareable content: %@", error?.localizedDescription ?? "unknown")
                return
            }

            let filter = SCContentFilter(display: content.displays.first!, excludingApplications: [], exceptingWindows: [])
            let stream = SCStream(filter: filter, configuration: config, delegate: self)

            do {
                try stream.addStreamOutput(self, type: .audio, sampleHandlerQueue: self.callbackQueue)
            } catch {
                NSLog("[AudioCaptureBridge] Failed to add stream output: %@", error.localizedDescription)
                return
            }
            stream.startCapture { error in
                if let error = error {
                    NSLog("[AudioCaptureBridge] Failed to start capture: %@", error.localizedDescription)
                } else {
                    self.stream = stream
                    NSLog("[AudioCaptureBridge] Capture started at %d Hz", sampleRate)
                }
            }
        }

        return true
    }

    /// Stop the active capture stream.
    @objc public func stopCapture() {
        guard #available(macOS 13.0, *) else { return }
        stream?.stopCapture { error in
            if let error = error {
                NSLog("[AudioCaptureBridge] Stop error: %@", error.localizedDescription)
            } else {
                NSLog("[AudioCaptureBridge] Capture stopped")
            }
        }
        stream = nil
        audioCallback = nil
    }

    // MARK: - SCStreamOutput

    @available(macOS 13.0, *)
    public func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio else { return }
        guard let callback = audioCallback else { return }
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }

        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
        else { return }
        let asbd = asbdPtr.pointee

        let sampleRate = Int32(asbd.mSampleRate)
        let channelCount = Int32(asbd.mChannelsPerFrame)

        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>? = nil
        let status = CMBlockBufferGetDataPointer(blockBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &totalLength, dataPointerOut: &dataPointer)

        guard status == kCMBlockBufferNoErr, let rawPtr = dataPointer else { return }

        let sampleCount = Int32(totalLength / MemoryLayout<Float32>.size)
        rawPtr.withMemoryRebound(to: Float32.self, capacity: Int(sampleCount)) { floatPtr in
            callback(sampleRate, channelCount, sampleCount, floatPtr)
        }
    }

    // MARK: - SCStreamDelegate

    @available(macOS 13.0, *)
    public func stream(_ stream: SCStream, didStopWithError error: Error) {
        NSLog("[AudioCaptureBridge] Stream stopped with error: %@", error.localizedDescription)
    }
}
