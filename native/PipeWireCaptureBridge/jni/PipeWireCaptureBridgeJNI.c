/*
 * PipeWire JNI bridge — Linux system audio capture via PipeWire monitor source.
 *
 * This file is a compilable stub that satisfies the JNI contract so the Kotlin layer
 * can compile and run on CI. nativeIsAvailable() returns JNI_FALSE so the Kotlin
 * PipeWireCaptureBackend gracefully falls back to silence when the real implementation
 * is not yet present.
 *
 * TODO: implement pw_stream capture:
 *   1. pw_init(NULL, NULL)
 *   2. pw_main_loop_new(NULL) → run in a dedicated pthread
 *   3. pw_stream_new_simple(loop, "agrapha-monitor", ...)
 *      with PW_KEY_STREAM_CAPTURE_SINK = "true" to tap the monitor/loopback source
 *   4. SPA audio format: SPA_AUDIO_FORMAT_F32, 1 channel, requested sampleRate
 *   5. on_process callback: copy F32 samples into ring_buffer under pthread_mutex_t
 *   6. nativeReadBuffer: copy available samples out of ring_buffer under lock
 *
 * Ring buffer spec: RING_SIZE = 16000 * 10 = 160,000 floats (10 seconds at 16kHz).
 * Lock type: pthread_mutex_t (POSIX, available everywhere without extra deps).
 */

#include "PipeWireCaptureBridgeJNI.h"
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

/* ── Ring buffer ─────────────────────────────────────────────────────────── */

#define RING_SIZE (16000 * 10)  /* 10 seconds at 16 kHz */

static float      ring_buffer[RING_SIZE];
static int        ring_write = 0;
static int        ring_read  = 0;
static int        ring_count = 0;
static pthread_mutex_t ring_mutex = PTHREAD_MUTEX_INITIALIZER;

static int        capture_running = 0;

/* ── Availability check ──────────────────────────────────────────────────── */

/*
 * Returns JNI_TRUE only when:
 *   - /proc/version exists (Linux kernel)
 *   - $XDG_RUNTIME_DIR/pipewire-0 socket exists
 *
 * TODO: when the full pw_stream implementation is done, also verify
 * that pw_init() succeeds and the default monitor node is accessible.
 */
JNIEXPORT jboolean JNICALL
Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeIsAvailable(JNIEnv* env, jobject thiz)
{
    /* Linux check */
    FILE* f = fopen("/proc/version", "r");
    if (!f) return JNI_FALSE;
    fclose(f);

    /* PipeWire socket check */
    const char* xdg_runtime = getenv("XDG_RUNTIME_DIR");
    if (!xdg_runtime) return JNI_FALSE;

    char path[512];
    snprintf(path, sizeof(path), "%s/pipewire-0", xdg_runtime);
    FILE* sock = fopen(path, "r");
    if (!sock) return JNI_FALSE;
    fclose(sock);

    /* TODO: return JNI_TRUE here once pw_stream implementation is complete */
    /* For now, return JNI_FALSE so Kotlin falls back to NoOpSystemAudioBackend */
    return JNI_FALSE;
}

/* ── Capture control ─────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStartCapture(JNIEnv* env, jobject thiz, jint sampleRate)
{
    /* TODO: implement pw_stream creation, connect with PW_KEY_STREAM_CAPTURE_SINK="true" */
    (void)env; (void)thiz; (void)sampleRate;
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeReadBuffer(JNIEnv* env, jobject thiz, jfloatArray buffer)
{
    /* TODO: drain ring_buffer into buffer under pthread_mutex_t lock */
    (void)thiz;
    if (!buffer) return 0;
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len <= 0) return 0;

    pthread_mutex_lock(&ring_mutex);
    int available = ring_count < len ? ring_count : (int)len;
    if (available > 0) {
        jfloat* arr = (*env)->GetFloatArrayElements(env, buffer, NULL);
        for (int i = 0; i < available; i++) {
            arr[i] = ring_buffer[ring_read];
            ring_read = (ring_read + 1) % RING_SIZE;
        }
        ring_count -= available;
        (*env)->ReleaseFloatArrayElements(env, buffer, arr, 0);
    }
    pthread_mutex_unlock(&ring_mutex);
    return available;
}

JNIEXPORT void JNICALL
Java_com_meetingnotes_audio_PipeWireCaptureJniBridge_nativeStopCapture(JNIEnv* env, jobject thiz)
{
    /* TODO: stop pw_main_loop, join capture pthread, destroy stream */
    (void)env; (void)thiz;
    capture_running = 0;
}
