// jni_bridge.c
// Implements the JNI helpers declared in jni_bridge.h.
// jni.h is available via -Xcc -I$JAVA_HOME/include -Xcc -I$JAVA_HOME/include/darwin.
#include "include/jni_bridge.h"
#include <jni.h>
#include <string.h>
#include <stdlib.h>

const char* jni_get_string_utf(JNIEnvPtr env, jstring str) {
    if (!env || !str) return NULL;
    JNIEnv* e = (JNIEnv*)env;
    return (*e)->GetStringUTFChars(e, (jstring)str, NULL);
}

void jni_release_string_utf(JNIEnvPtr env, jstring str, const char* chars) {
    if (!env || !str || !chars) return;
    JNIEnv* e = (JNIEnv*)env;
    (*e)->ReleaseStringUTFChars(e, (jstring)str, chars);
}

jstring jni_new_string_utf(JNIEnvPtr env, const char* str) {
    if (!env || !str) return NULL;
    JNIEnv* e = (JNIEnv*)env;
    return (jstring)(*e)->NewStringUTF(e, str);
}
