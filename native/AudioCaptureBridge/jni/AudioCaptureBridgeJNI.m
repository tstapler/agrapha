#import "AudioCaptureBridgeJNI.h"
// Found via -I../Sources/AudioCaptureBridge in CFLAGS
#import "AudioCaptureBridge.h"
#import <dispatch/dispatch.h>
#import <os/lock.h>

// Ring buffer for PCM samples shared between Swift callback and JVM reader.
#define RING_BUFFER_CAPACITY (16000 * 10)  // 10 seconds at 16kHz

static float gRingBuffer[RING_BUFFER_CAPACITY];
static volatile int gWritePos = 0;
static volatile int gReadPos = 0;
static os_unfair_lock gLock = OS_UNFAIR_LOCK_INIT;

/// Swift audio callback — writes PCM samples into ring buffer.
static void audioCallback(int32_t sampleRate, int32_t channelCount, int32_t sampleCount, const float *samples) {
    os_unfair_lock_lock(&gLock);
    for (int i = 0; i < sampleCount; i++) {
        gRingBuffer[gWritePos % RING_BUFFER_CAPACITY] = samples[i];
        gWritePos++;
    }
    os_unfair_lock_unlock(&gLock);
}

JNIEXPORT jboolean JNICALL
Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeCheckPermission(JNIEnv *env, jobject obj) {
    return (jboolean)[[AudioCaptureBridgeObjC shared] checkPermission];
}

JNIEXPORT jboolean JNICALL
Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeRequestPermission(JNIEnv *env, jobject obj) {
    __block BOOL result = NO;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);

    [[AudioCaptureBridgeObjC shared] requestPermissionWithCompletion:^(BOOL granted) {
        result = granted;
        dispatch_semaphore_signal(sem);
    }];

    // Wait up to 30 seconds for the permission dialog
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, 30LL * NSEC_PER_SEC);
    dispatch_semaphore_wait(sem, timeout);

    return (jboolean)result;
}

JNIEXPORT jboolean JNICALL
Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeStartCapture(JNIEnv *env, jobject obj, jint sampleRate) {
    gWritePos = 0;
    gReadPos = 0;
    BOOL started = [[AudioCaptureBridgeObjC shared] startCaptureWithSampleRate:(int32_t)sampleRate callback:audioCallback];
    return (jboolean)started;
}

JNIEXPORT void JNICALL
Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeStopCapture(JNIEnv *env, jobject obj) {
    [[AudioCaptureBridgeObjC shared] stopCapture];
}

JNIEXPORT jint JNICALL
Java_com_meetingnotes_audio_ScreenCaptureJniBridge_nativeReadBuffer(JNIEnv *env, jobject obj, jfloatArray outBuffer) {
    jsize capacity = (*env)->GetArrayLength(env, outBuffer);
    jfloat *dst = (*env)->GetFloatArrayElements(env, outBuffer, NULL);
    if (!dst) return 0;

    os_unfair_lock_lock(&gLock);
    int available = gWritePos - gReadPos;
    int toRead = available < (int)capacity ? available : (int)capacity;
    for (int i = 0; i < toRead; i++) {
        dst[i] = gRingBuffer[(gReadPos + i) % RING_BUFFER_CAPACITY];
    }
    gReadPos += toRead;
    os_unfair_lock_unlock(&gLock);

    (*env)->ReleaseFloatArrayElements(env, outBuffer, dst, 0);
    return (jint)toRead;
}
