#ifndef AudioCaptureBridge_h
#define AudioCaptureBridge_h

#import <Foundation/Foundation.h>

/// C-compatible audio callback type.
/// Called on each PCM audio buffer with Float32 samples.
typedef void (*AudioPCMCallback)(
    int32_t sampleRate,
    int32_t channelCount,
    int32_t sampleCount,
    const float * _Nonnull samples
);

/// Obj-C-compatible interface for JNI consumption.
@interface AudioCaptureBridgeObjC : NSObject

+ (instancetype _Nonnull)shared;

/// Synchronous preflight check — returns YES if permission is already granted, NO otherwise.
/// Does NOT show a dialog.
- (BOOL)checkPermission;

/// Request ScreenCaptureKit permission (triggers TCC dialog on first call).
/// completion is called on an arbitrary background queue.
- (void)requestPermissionWithCompletion:(void (^ _Nonnull)(BOOL granted))completion;

/// Start system audio capture at the given sample rate.
/// callback is called for each PCM Float32 buffer.
- (BOOL)startCaptureWithSampleRate:(int32_t)sampleRate callback:(AudioPCMCallback _Nullable)callback;

/// Stop the active capture session.
- (void)stopCapture;

@end

#endif /* AudioCaptureBridge_h */
