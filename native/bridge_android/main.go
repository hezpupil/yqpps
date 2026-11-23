//go:build android

package main

/*
#include <jni.h>
#include <stdlib.h>

static const char *yqGetString(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, s, 0);
}
static void yqReleaseString(JNIEnv *env, jstring s, const char *c) {
    if (c != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
}
static jstring yqNewString(JNIEnv *env, const char *s) {
    jstring r = (*env)->NewStringUTF(env, s);
    free((void *)s);
    return r;
}
*/
import "C"
import "duanjuapp/native/core"

//export Java_com_youqu_vod_duanju_NativeCore_nativeRequest
func Java_com_youqu_vod_duanju_NativeCore_nativeRequest(env *C.JNIEnv, clazz C.jclass, input C.jstring) C.jstring {
	if input == 0 {
		return C.yqNewString(env, C.CString(`{"ok":false,"error":"请求为空"}`))
	}
	cstr := C.yqGetString(env, input)
	if cstr == nil {
		return C.yqNewString(env, C.CString(`{"ok":false,"error":"请求为空"}`))
	}
	raw := C.GoString(cstr)
	C.yqReleaseString(env, input, cstr)
	return C.yqNewString(env, C.CString(core.NativeRequest(raw)))
}

func main() {}
