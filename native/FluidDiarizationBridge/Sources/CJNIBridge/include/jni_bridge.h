// jni_bridge.h
// Wraps JNI's double-pointer env type behind simple void* helpers so Swift code
// can stay clean. The actual jni.h is pulled from JAVA_HOME at build time via
// -Xcc -I$JAVA_HOME/include -Xcc -I$JAVA_HOME/include/darwin.
#pragma once

// Opaque type aliases — Swift will see these as UnsafeMutableRawPointer.
// Keep them as void* so Swift doesn't need to know the JNI struct layout.
typedef void* JNIEnvPtr;

// Guard against redefinition when jni.h is included before this header (e.g. in jni_bridge.c).
// JNIEXPORT is defined by jni.h, so its presence means the real JNI types are already in scope.
#ifndef JNIEXPORT
typedef void* jobject;
typedef jobject jstring;
typedef unsigned char jboolean;
typedef int jint;
typedef long long jlong;
#endif

#ifdef __cplusplus
extern "C" {
#endif

/// Returns a UTF-8 C string for a JNI string reference.
/// Caller must pass the pointer to jni_release_string_utf when done.
const char* jni_get_string_utf(JNIEnvPtr env, jstring str);

/// Releases the C string obtained from jni_get_string_utf.
void jni_release_string_utf(JNIEnvPtr env, jstring str, const char* chars);

/// Creates a new JNI string from a null-terminated UTF-8 C string.
/// Returns NULL if env is NULL or str is NULL.
jstring jni_new_string_utf(JNIEnvPtr env, const char* str);

#ifdef __cplusplus
}
#endif
